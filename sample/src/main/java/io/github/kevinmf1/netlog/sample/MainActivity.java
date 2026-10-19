package io.github.kevinmf1.netlog.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import okhttp3.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ServerSocket server;
    private OkHttpClient client;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(40, 80, 40, 40);
        status = new TextView(this); status.setText("NetLog sample\nLocal test traffic, no account or external server required."); status.setTextSize(20); layout.addView(status);
        Button send = new Button(this); send.setText("Send test requests"); layout.addView(send); setContentView(layout);
        client = new OkHttpClient.Builder().build();
        send.setOnClickListener(v -> send());
        try {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            Thread responder = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String first = reader.readLine(); if (first == null) continue;
                        int length = 0; String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                        }
                        while (length-- > 0 && reader.read() != -1) { }
                        int code = first.contains("/unauthorized") ? 401 : first.contains("/unavailable") ? 503 : 200;
                        byte[] body = ("{\"source\":\"NetLog local sample\",\"status\":" + code + ",\"items\":[{\"name\":\"Coffee\",\"quantity\":2}]}").getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 " + code + " Sample\r\nContent-Type: application/json\r\nSet-Cookie: sample-secret\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().write(body);
                    } catch (Exception ignored) { }
                }
            }, "Sample HTTP server"); responder.setDaemon(true); responder.start(); send();
        } catch (IOException error) { status.setText(error.toString()); }
    }
    private void send() {
        worker.execute(() -> {
            try {
                for (String path : new String[]{"/products", "/unauthorized", "/unavailable", "/cart"}) {
                    Request.Builder request = new Request.Builder().url("http://127.0.0.1:" + server.getLocalPort() + path).header("Authorization", "Bearer sample-secret");
                    if (path.equals("/cart")) request.post(RequestBody.create("{\"product\":\"coffee\",\"quantity\":2}", MediaType.get("application/json")));
                    OkHttpClient selected = path.equals("/unauthorized") ? new OkHttpClient() : path.equals("/unavailable") ? client.newBuilder().build() : client;
                    try (Response response = selected.newCall(request.build()).execute()) { response.body().string(); }
                }
                runOnUiThread(() -> status.setText("4 test requests sent.\nOpen NetLog on your computer. This emulator or phone will appear automatically."));
            } catch (Exception error) { runOnUiThread(() -> status.setText(error.toString())); }
        });
    }
    @Override protected void onDestroy() {
        super.onDestroy(); worker.shutdownNow();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }
}
