using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Channels;
using System.Windows;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Hosting;
using NAudio.Wave;

namespace Hearm.Windows;

public sealed class AudioStreamHost : IAsyncDisposable
{
    private readonly ConcurrentDictionary<WebSocket, SemaphoreSlim> _clients = new();
    private readonly Channel<byte[]> _audio = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(64) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true, SingleWriter = false });
    private readonly WasapiLoopbackCapture _capture = new();
    private readonly string _pairingKey = Convert.ToHexString(RandomNumberGenerator.GetBytes(4)).ToLowerInvariant();
    private readonly string _hostIp;
    private WebApplication? _app;
    private CancellationTokenSource _stop = new();
    private Task? _pump;
    private UdpClient? _discovery;
    private CancellationTokenSource? _discoveryStop;
    private Task? _discoveryTask;
    private readonly int _sampleRate;
    public string PairingCode => _pairingKey;
    public string BuildAddress(string ip) => $"ws://{ip}:4174/audio?key={_pairingKey}";

    public AudioStreamHost(string hostIp)
    {
        _hostIp = hostIp;
        _sampleRate = _capture.WaveFormat.SampleRate;
        _capture.DataAvailable += (_, e) => { var pcm = ToMonoPcm16(e.Buffer, e.BytesRecorded, _capture.WaveFormat); if (pcm.Length > 0) _audio.Writer.TryWrite(pcm); };
    }
    public async Task StartAsync()
    {
        try
        {
            _discoveryStop = new CancellationTokenSource();
            _discovery = new UdpClient(new IPEndPoint(IPAddress.Any, 4175));
            _discoveryTask = Task.Run(() => DiscoveryLoopAsync(_discoveryStop.Token));
        }
        catch { _discovery?.Dispose(); _discovery = null; }
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseUrls("http://0.0.0.0:4174");
        _app = builder.Build(); _app.UseWebSockets();
        _app.MapGet("/", () => Results.Text("Hearm audio link is ready."));
        _app.Map("/audio", HandleClientAsync);
        await _app.StartAsync();
        _pump = Task.Run(PumpAsync);
        _capture.StartRecording();
    }
    private async Task DiscoveryLoopAsync(CancellationToken cancellationToken)
    {
        if (_discovery is null) return;
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var request = await _discovery.ReceiveAsync(cancellationToken);
                if (Encoding.UTF8.GetString(request.Buffer) != "HEARM_DISCOVER_V1") continue;
                var response = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new { type = "hearm-device", name = Environment.MachineName, host = _hostIp, port = 4174, key = _pairingKey });
                await _discovery.SendAsync(response, request.RemoteEndPoint, cancellationToken);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) when (cancellationToken.IsCancellationRequested) { break; }
            catch { try { await Task.Delay(400, cancellationToken); } catch (OperationCanceledException) { break; } }
        }
    }
    private async Task HandleClientAsync(HttpContext context)
    {
        if (!CryptographicOperations.FixedTimeEquals(System.Text.Encoding.ASCII.GetBytes(context.Request.Query["key"].ToString()), System.Text.Encoding.ASCII.GetBytes(_pairingKey))) { context.Response.StatusCode = 403; return; }
        if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        var sendLock = new SemaphoreSlim(1, 1); _clients[socket] = sendLock;
        try
        {
            var config = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new { sampleRate = _sampleRate, channels = 1, encoding = "pcm_s16le" });
            await socket.SendAsync(config, WebSocketMessageType.Text, true, context.RequestAborted);
            var buffer = new byte[128];
            while (socket.State == WebSocketState.Open) { var result = await socket.ReceiveAsync(buffer, context.RequestAborted); if (result.MessageType == WebSocketMessageType.Close) break; }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
        finally { _clients.TryRemove(socket, out _); sendLock.Dispose(); }
    }
    private async Task PumpAsync()
    {
        try
        {
            await foreach (var packet in _audio.Reader.ReadAllAsync(_stop.Token))
            {
                foreach (var pair in _clients.ToArray())
                {
                    var socket = pair.Key; if (socket.State != WebSocketState.Open) continue;
                    try { await pair.Value.WaitAsync(_stop.Token); try { await socket.SendAsync(packet, WebSocketMessageType.Binary, true, _stop.Token); } finally { pair.Value.Release(); } }
                    catch (OperationCanceledException) { return; }
                    catch (Exception) { _clients.TryRemove(socket, out _); }
                }
            }
        }
        catch (OperationCanceledException) { }
    }
    private static byte[] ToMonoPcm16(byte[] source, int length, NAudio.Wave.WaveFormat format)
    {
        var sampleBytes = Math.Max(1, format.BitsPerSample / 8); var frameBytes = sampleBytes * format.Channels; var frames = length / frameBytes;
        if (frames == 0) return [];
        var result = new byte[frames * 2];
        var floatSubformat = format is WaveFormatExtensible extensible && extensible.SubFormat.ToString().Equals("00000003-0000-0010-8000-00aa00389b71", StringComparison.OrdinalIgnoreCase);
        for (var frame = 0; frame < frames; frame++)
        {
            double sum = 0;
            for (var ch = 0; ch < format.Channels; ch++)
            {
                var offset = frame * frameBytes + ch * sampleBytes;
                if (format.BitsPerSample == 32 && (format.Encoding == WaveFormatEncoding.IeeeFloat || floatSubformat)) sum += BitConverter.ToSingle(source, offset);
                else if (format.BitsPerSample == 16) sum += BitConverter.ToInt16(source, offset) / 32768.0;
                else if (format.BitsPerSample == 24) { var raw = source[offset] | (source[offset + 1] << 8) | (source[offset + 2] << 16); if ((raw & 0x800000) != 0) raw |= unchecked((int)0xff000000); sum += raw / 8388608.0; }
                else if (format.BitsPerSample == 32) sum += BitConverter.ToInt32(source, offset) / 2147483648.0;
            }
            var value = (short)Math.Clamp(sum / format.Channels * short.MaxValue, short.MinValue, short.MaxValue);
            result[frame * 2] = (byte)(value & 0xff); result[frame * 2 + 1] = (byte)((value >> 8) & 0xff);
        }
        return result;
    }
    public async ValueTask DisposeAsync()
    {
        _stop.Cancel(); _audio.Writer.TryComplete();
        if (_discoveryStop is not null) _discoveryStop.Cancel();
        _discovery?.Dispose();
        if (_discoveryTask is not null) try { await _discoveryTask; } catch { }
        try { _capture.StopRecording(); } catch { }
        _capture.Dispose();
        foreach (var socket in _clients.Keys) { try { socket.Abort(); } catch { } }
        if (_pump is not null) try { await _pump; } catch { }
        if (_app is not null) { await _app.StopAsync(); await _app.DisposeAsync(); }
        _stop.Dispose();
        _discoveryStop?.Dispose();
    }
}
