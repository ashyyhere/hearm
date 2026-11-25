using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Windows;

namespace Hearm.Windows;

public partial class MainWindow : Window
{
    private AudioStreamHost? _host;
    private readonly string? _ip;
    public MainWindow()
    {
        InitializeComponent();
        _ip = GetLanAddress();
        _host = new AudioStreamHost(_ip ?? string.Empty);
        UpdateAddress();
        Closed += async (_, _) => { if (_host is not null) await _host.DisposeAsync(); };
    }
    private void UpdateAddress() => AddressText.Text = _ip is null ? "Connect this PC to Wi-Fi" : $"LAPTOP ADDRESS   {_ip}\nPAIRING CODE      {_host!.PairingCode}";
    private async void StartButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            StartButton.IsEnabled = false;
            _host ??= new AudioStreamHost(_ip ?? string.Empty);
            await _host.StartAsync();
            StopButton.IsEnabled = true;
            StatusText.Text = "Sharing system audio. Connect the Android phone now.";
        }
        catch (Exception ex)
        {
            StatusText.Text = "Could not start audio sharing: " + ex.Message;
            StartButton.IsEnabled = true;
            if (_host is not null) await _host.DisposeAsync();
            _host = new AudioStreamHost(_ip ?? string.Empty); UpdateAddress();
        }
    }
    private async void StopButton_Click(object sender, RoutedEventArgs e)
    {
        if (_host is not null) await _host.DisposeAsync();
        _host = new AudioStreamHost(_ip ?? string.Empty); UpdateAddress(); StartButton.IsEnabled = true; StopButton.IsEnabled = false; StatusText.Text = "Sharing stopped.";
    }
    private static string? GetLanAddress()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up).OrderByDescending(n => n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211))
            foreach (var address in nic.GetIPProperties().UnicastAddresses)
                if (address.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address.Address) && !address.Address.ToString().StartsWith("169.254.")) return address.Address.ToString();
        return null;
    }
}
