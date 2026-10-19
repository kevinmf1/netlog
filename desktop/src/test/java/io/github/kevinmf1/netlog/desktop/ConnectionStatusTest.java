package io.github.kevinmf1.netlog.desktop;

import org.junit.Assume;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ConnectionStatusTest {
    @Test(timeout = 15000) public void reportsOnlyDevicesWithSuccessfulCaptureAsLive() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path directory = Files.createTempDirectory("netlog-adb-test");
        Path executable = directory.resolve("adb");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(4000);
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    String batch = "{\"type\":\"hello\",\"version\":1,\"session\":\"test\",\"package\":\"example.app\",\"app\":\"Example\"}\n"
                        + "{\"type\":\"end\",\"cursor\":0}\n";
                    socket.getOutputStream().write(batch.getBytes(StandardCharsets.UTF_8));
                } catch (Throwable error) { failure.set(error); }
            }, "Test capture server");
            responder.setDaemon(true); responder.start();
            String script = """
                #!/bin/sh
                case "$*" in
                  "devices -l") printf 'List of devices attached\\nready-1 device model:test\\nfailed-2 device model:test\\n' ;;
                  "-s ready-1 shell cat /proc/net/unix") printf '0000: 00000002 00000000 00010000 0001 01 42 @netlog.v1.example.1\\n' ;;
                  "-s ready-1 forward tcp:0 localabstract:netlog.v1.example.1") echo %d ;;
                  "-s failed-2 shell cat /proc/net/unix") echo 'device shell unavailable' >&2; exit 1 ;;
                  *"forward --remove"*) exit 0 ;;
                  *) exit 1 ;;
                esac
                """.formatted(server.getLocalPort());
            Files.writeString(executable, script);
            assertTrue(executable.toFile().setExecutable(true));
            try (AdbBridge bridge = new AdbBridge()) {
                bridge.setExecutable(executable.toString());
                AdbBridge.Batch batch = bridge.poll();
                assertTrue(batch.status(), batch.status().startsWith("Live capture on ready-1 (1 app session(s))."));
                assertTrue(batch.status(), batch.status().contains("failed-2: device shell unavailable"));
                assertTrue(batch.records().isEmpty());
            }
            responder.join(2000);
            assertFalse(responder.isAlive()); assertNull(failure.get());
        } finally { Files.deleteIfExists(executable); Files.deleteIfExists(directory); }
    }
}
