package com.hearm.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class MainActivity extends Activity {
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build();
    private EditText address;
    private EditText pairingCode;
    private TextView status;
    private Button connectButton;
    private Button discoverButton;
    private LinearLayout nearbyList;
    private WebSocket socket;
    private AudioTrack track;
    private boolean connected;
    private final ExecutorService discoveryWorker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(244, 243, 237));
        getWindow().setNavigationBarColor(Color.rgb(244, 243, 237));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER_VERTICAL); page.setPadding(dp(28), dp(24), dp(28), dp(24)); page.setBackgroundColor(Color.rgb(244, 243, 237));
        LinearLayout brandRow = new LinearLayout(this); brandRow.setOrientation(LinearLayout.HORIZONTAL); brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandMark = new ImageView(this); brandMark.setImageResource(R.drawable.hearm_mark); LinearLayout.LayoutParams marklp = params(27, 27); marklp.rightMargin = dp(9); brandRow.addView(brandMark, marklp);
        TextView brand = new TextView(this); brand.setText("hearm"); brand.setTextSize(27); brand.setTextColor(Color.rgb(34, 40, 32)); brand.setTypeface(null, 1); brandRow.addView(brand); page.addView(brandRow);
        TextView subtitle = new TextView(this); subtitle.setText("Your laptop audio, on your phone."); subtitle.setTextSize(14); subtitle.setTextColor(Color.rgb(119, 128, 113)); LinearLayout.LayoutParams sublp = params(-1, -2); sublp.topMargin = dp(5); page.addView(subtitle, sublp);

        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(20), dp(24), dp(20), dp(24)); card.setBackgroundColor(Color.WHITE); LinearLayout.LayoutParams cardlp = params(-1, -2); cardlp.topMargin = dp(30); cardlp.bottomMargin = dp(22); page.addView(card, cardlp);
        TextView title = new TextView(this); title.setText("JOIN YOUR LAPTOP"); title.setTextSize(10); title.setLetterSpacing(.12f); title.setTextColor(Color.rgb(94, 117, 83)); title.setTypeface(null, 1); card.addView(title);
        TextView copy = new TextView(this); copy.setText("Find your Windows laptop nearby and connect with one tap."); copy.setTextSize(14); copy.setTextColor(Color.rgb(80, 86, 76)); LinearLayout.LayoutParams copylp = params(-1, -2); copylp.topMargin = dp(13); card.addView(copy, copylp);
        discoverButton = new Button(this); discoverButton.setText("Find nearby laptops"); discoverButton.setTextColor(Color.WHITE); discoverButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(96, 124, 84))); LinearLayout.LayoutParams findlp = params(-1, -2); findlp.topMargin = dp(14); card.addView(discoverButton, findlp);
        nearbyList = new LinearLayout(this); nearbyList.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams listlp = params(-1, -2); listlp.topMargin = dp(4); card.addView(nearbyList, listlp);
        TextView manual = new TextView(this); manual.setText("Or connect manually"); manual.setTextSize(10); manual.setTextColor(Color.rgb(133, 138, 128)); LinearLayout.LayoutParams manuallp = params(-1, -2); manuallp.topMargin = dp(10); card.addView(manual, manuallp);
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(13); address.setHint("Laptop address, e.g. 192.168.1.10"); address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); address.setPadding(dp(11), dp(9), dp(11), dp(9)); LinearLayout.LayoutParams inputlp = params(-1, -2); inputlp.topMargin = dp(18); card.addView(address, inputlp);
        pairingCode = new EditText(this); pairingCode.setSingleLine(true); pairingCode.setTextSize(13); pairingCode.setHint("8-character pairing code"); pairingCode.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); pairingCode.setPadding(dp(11), dp(9), dp(11), dp(9)); LinearLayout.LayoutParams codelp = params(-1, -2); codelp.topMargin = dp(9); card.addView(pairingCode, codelp);
        connectButton = new Button(this); connectButton.setText("Connect and listen"); connectButton.setTextColor(Color.WHITE); connectButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(96, 124, 84))); LinearLayout.LayoutParams btnlp = params(-1, -2); btnlp.topMargin = dp(12); card.addView(connectButton, btnlp);
        status = new TextView(this); status.setText("Your phone apps stay local; incoming audio plays here."); status.setTextSize(11); status.setTextColor(Color.rgb(133, 138, 128)); LinearLayout.LayoutParams statuslp = params(-1, -2); statuslp.topMargin = dp(16); card.addView(status, statuslp);
        TextView foot = new TextView(this); foot.setText("Keep earbuds connected to this phone. Both devices need the same Wi-Fi."); foot.setTextSize(11); foot.setTextColor(Color.rgb(133, 138, 128)); foot.setGravity(Gravity.CENTER); page.addView(foot);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
        connectButton.setOnClickListener(v -> { if (connected) disconnect(); else connect(); });
        discoverButton.setOnClickListener(v -> findNearbyLaptops());
    }

    private void findNearbyLaptops() {
        discoverButton.setEnabled(false); discoverButton.setText("Searching nearby…"); nearbyList.removeAllViews();
        status.setText("Looking for Hearm on your Wi-Fi…");
        discoveryWorker.execute(() -> {
            LinkedHashMap<String, JSONObject> devices = new LinkedHashMap<>();
            WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("HearmNearbyScan");
            try {
                if (lock != null) { lock.setReferenceCounted(false); lock.acquire(); }
                try (DatagramSocket udp = new DatagramSocket()) {
                    udp.setBroadcast(true); udp.setSoTimeout(400);
                    byte[] probe = "HEARM_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);
                    long end = System.currentTimeMillis() + 3200;
                    while (System.currentTimeMillis() < end) {
                        udp.send(new DatagramPacket(probe, probe.length, InetAddress.getByName("255.255.255.255"), 4175));
                        long listenUntil = System.currentTimeMillis() + 500;
                        while (System.currentTimeMillis() < listenUntil) {
                            byte[] buf = new byte[2048]; DatagramPacket reply = new DatagramPacket(buf, buf.length);
                            try {
                                udp.receive(reply); JSONObject info = new JSONObject(new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8));
                                if ("hearm-device".equals(info.optString("type")) && info.has("host") && info.has("key")) devices.put(info.optString("host"), info);
                            } catch (SocketTimeoutException ignored) { }
                        }
                    }
                }
            } catch (Exception ignored) { }
            finally { if (lock != null && lock.isHeld()) lock.release(); }
            runOnUiThread(() -> showNearbyDevices(new ArrayList<>(devices.values())));
        });
    }

    private void showNearbyDevices(ArrayList<JSONObject> devices) {
        discoverButton.setEnabled(true); discoverButton.setText("Search again"); nearbyList.removeAllViews();
        for (JSONObject info : devices) {
            String host = info.optString("host"), name = info.optString("name", "Hearm laptop"), key = info.optString("key");
            Button item = new Button(this); item.setText(name + "  ·  " + host); item.setAllCaps(false); item.setTextColor(Color.rgb(60, 89, 50));
            item.setOnClickListener(v -> { address.setText(host); pairingCode.setText(key); connect(); });
            nearbyList.addView(item, params(-1, -2));
        }
        status.setText(devices.isEmpty() ? "No laptops found. Start sharing in the Windows app and try again." : "Tap your laptop to connect.");
    }

    private void connect() {
        String host = address.getText().toString().trim();
        String key = pairingCode.getText().toString().trim();
        if (host.isEmpty()) { address.setError("Enter the laptop address shown in the Windows app"); return; }
        if (key.length() != 8) { pairingCode.setError("Enter the 8-character code shown in the Windows app"); return; }
        String url = "ws://" + host + ":4174/audio?key=" + key;
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
        connectButton.setEnabled(false); status.setText("Connecting over Wi-Fi…");
        Request request = new Request.Builder().url(url).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) { runOnUiThread(() -> { connected = true; connectButton.setEnabled(true); connectButton.setText("Disconnect"); status.setText("Connected. Laptop audio is live."); }); }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject config = new JSONObject(text); int rate = config.getInt("sampleRate");
                    runOnUiThread(() -> startOutput(rate));
                } catch (Exception e) { runOnUiThread(() -> status.setText("The laptop sent an unknown audio format.")); }
            }
            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                AudioTrack output = track;
                if (output != null && output.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) output.write(bytes.toByteArray(), 0, bytes.size(), AudioTrack.WRITE_BLOCKING);
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { runOnUiThread(() -> finishConnection("Laptop disconnected.")); }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) { runOnUiThread(() -> finishConnection("Could not connect. Check the address and Wi-Fi.")); }
        });
    }

    private void startOutput(int sampleRate) {
        try {
            releaseTrack();
            int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min * 4, sampleRate / 5 * 2);
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat format = new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            track = new AudioTrack(attrs, format, buffer, AudioTrack.MODE_STREAM, 0);
            track.play(); status.setText("Live. Laptop sound is playing through this phone.");
        } catch (Exception e) { status.setText("Could not start audio playback on this phone."); }
    }

    private void disconnect() { if (socket != null) socket.close(1000, "User disconnected"); finishConnection("Disconnected."); }
    private void finishConnection(String message) { connected = false; connectButton.setEnabled(true); connectButton.setText("Connect and listen"); status.setText(message); releaseTrack(); }
    private void releaseTrack() { if (track != null) { try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) {} track = null; } }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout.LayoutParams params(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    @Override protected void onDestroy() { if (socket != null) socket.close(1000, "App closed"); releaseTrack(); discoveryWorker.shutdownNow(); client.dispatcher().executorService().shutdown(); super.onDestroy(); }
}
