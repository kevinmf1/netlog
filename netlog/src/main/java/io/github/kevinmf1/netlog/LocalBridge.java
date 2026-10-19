package io.github.kevinmf1.netlog;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class LocalBridge {
    private static boolean started;

    static synchronized void start(Context context) {
        if (started || (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        started = true;
        String packageName = context.getPackageName();
        String label = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
        Thread thread = new Thread(() -> serve(packageName, label), "NetLog bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private static void serve(String packageName, String label) {
        String session = UUID.randomUUID().toString();
        String name = "netlog.v1." + packageName + "." + Process.myPid();
        if (name.length() > 100) name = "netlog.v1." + Integer.toHexString(packageName.hashCode()) + "." + Process.myPid();
        try (LocalServerSocket server = new LocalServerSocket(name)) {
            String hello = new JSONObject().put("type", "hello").put("version", 1)
                .put("session", session).put("package", packageName).put("app", label).toString();
            while (true) {
                try (LocalSocket socket = server.accept()) {
                    int uid = socket.getPeerCredentials().getUid();
                    if (uid != 0 && uid != 2000 && uid != Process.myUid()) continue;
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder line = new StringBuilder();
                    for (int c; (c = reader.read()) != -1 && c != '\n' && line.length() < 24;) line.append((char) c);
                    long cursor = Long.parseLong(line.toString());
                    OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                    writer.write(hello + "\n");
                    for (String record : CaptureStore.INSTANCE.since(cursor)) writer.write(record + "\n");
                    writer.flush();
                } catch (Exception ignored) { /* A disconnected inspector must not stop discovery. */ }
            }
        } catch (Exception ignored) {
            synchronized (LocalBridge.class) { started = false; }
        }
    }
}
