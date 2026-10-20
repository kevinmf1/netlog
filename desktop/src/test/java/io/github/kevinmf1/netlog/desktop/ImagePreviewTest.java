package io.github.kevinmf1.netlog.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.Assume;
import org.junit.Test;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.Assert.*;

public class ImagePreviewTest {
    private static JsonObject record(String method, int status, String url, String contentType) {
        JsonObject record = new JsonObject();
        record.addProperty("id", 1); record.addProperty("time", 1); record.addProperty("key", "test/session/1");
        record.addProperty("session", "session"); record.addProperty("device", "test"); record.addProperty("app", "Test");
        record.addProperty("method", method); record.addProperty("status", status); record.addProperty("url", url);
        if (contentType != null) {
            JsonArray headers = new JsonArray(); JsonArray pair = new JsonArray(); pair.add("Content-Type"); pair.add(contentType); headers.add(pair);
            record.add("responseHeaders", headers);
        }
        return record;
    }

    @Test public void detectsImageResponses() {
        assertTrue(NetLogDesktop.isImage(record("GET", 200, "https://cdn.test/a", "image/png")));
        assertTrue(NetLogDesktop.isImage(record("GET", 200, "https://cdn.test/coupon/a.JPG?w=200", null)));
        assertFalse("Only successful GETs are previewed", NetLogDesktop.isImage(record("POST", 200, "https://cdn.test/a.png", "image/png")));
        assertFalse(NetLogDesktop.isImage(record("GET", 404, "https://cdn.test/a.png", "image/png")));
        assertFalse(NetLogDesktop.isImage(record("GET", 200, "https://api.test/items", "application/json")));
    }

    @Test public void downloadsImagesAndExplainsFailures() throws Exception {
        BufferedImage source = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(source, "png", png);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.png", exchange -> { exchange.getResponseHeaders().add("Content-Type", "image/png"); exchange.sendResponseHeaders(200, png.size()); exchange.getResponseBody().write(png.toByteArray()); exchange.close(); });
        server.createContext("/private.png", exchange -> {
            boolean authorized = "Bearer app-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            if (authorized) { exchange.sendResponseHeaders(200, png.size()); exchange.getResponseBody().write(png.toByteArray()); } else exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.createContext("/photo.webp", exchange -> { byte[] body = "RIFF....WEBP".getBytes(); exchange.getResponseHeaders().add("Content-Type", "image/webp"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonArray none = new JsonArray();
            Object ok = NetLogDesktop.fetchImage(base + "/ok.png", none);
            assertTrue(ok instanceof BufferedImage);
            assertEquals(40, ((BufferedImage) ok).getWidth());
            String denied = NetLogDesktop.fetchImage(base + "/private.png", none).toString();
            assertTrue(denied, denied.contains("401"));
            JsonArray appHeaders = new JsonArray();
            for (String[] header : new String[][] {{"Authorization", "Bearer app-token"}, {"Host", "ignored.test"}, {"Accept-Encoding", "gzip"}}) {
                JsonArray pair = new JsonArray(); pair.add(header[0]); pair.add(header[1]); appHeaders.add(pair);
            }
            assertTrue("The app's own headers are replayed", NetLogDesktop.fetchImage(base + "/private.png", appHeaders) instanceof BufferedImage);
            String webp = NetLogDesktop.fetchImage(base + "/photo.webp", none).toString();
            assertTrue(webp, webp.contains("image/webp"));
        } finally { server.stop(0); }
    }

    @Test public void masksSecretsUntilRevealed() throws Exception {
        assertEquals("https://[REDACTED]@api.test/a?access_token=[REDACTED]&page=2#top",
            NetLogDesktop.maskUrl("https://user:pass@api.test/a?access_token=abc&page=2#top"));
        assertEquals("https://api.test/a/b@c", NetLogDesktop.maskUrl("https://api.test/a/b@c"));
        JsonObject raw = record("GET", 200, "https://api.test/a?apikey=k1", "application/json");
        JsonArray auth = new JsonArray(); auth.add("Authorization"); auth.add("Bearer real");
        JsonArray apiKey = new JsonArray(); apiKey.add("api-key"); apiKey.add("test-api-secret");
        JsonArray requestHeaders = new JsonArray(); requestHeaders.add(auth); requestHeaders.add(apiKey); raw.add("requestHeaders", requestHeaders);
        JsonArray responseKey = new JsonArray(); responseKey.add("Api-Key"); responseKey.add("test-response-secret");
        raw.getAsJsonArray("responseHeaders").add(responseKey);
        JsonObject masked = NetLogDesktop.masked(raw);
        assertFalse(masked.toString(), masked.toString().contains("Bearer real") || masked.toString().contains("k1"));
        assertTrue("The original record keeps its values", raw.toString().contains("Bearer real"));
        assertTrue(raw.toString().contains("test-api-secret"));
        assertTrue(raw.toString().contains("test-response-secret"));
        assertTrue(NetLogDesktop.detail(masked, "request").contains("api-key: [REDACTED]"));
        assertTrue(NetLogDesktop.detail(masked, "response").contains("Api-Key: [REDACTED]"));
        assertTrue(NetLogDesktop.detail(masked, "response").contains("Content-Type: application/json"));
        assertFalse(NetLogDesktop.curl(masked).contains("test-api-secret"));
        assertTrue(NetLogDesktop.curl(raw).contains("test-api-secret"));

        Assume.assumeFalse("Requires a graphics environment for an invisible JFrame", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            NetLogDesktop app = new NetLogDesktop();
            try {
                Method ingest = NetLogDesktop.class.getDeclaredMethod("ingest", AdbBridge.Batch.class); ingest.setAccessible(true);
                ingest.invoke(app, new AdbBridge.Batch(List.of(raw), "test"));
                JTextArea request = field(app, "request"); JToggleButton secrets = field(app, "secrets");
                assertTrue(request.getText().contains("Authorization: [REDACTED]"));
                assertTrue(request.getText().contains("api-key: [REDACTED]"));
                assertTrue(((JTextArea) field(app, "response")).getText().contains("Api-Key: [REDACTED]"));
                secrets.doClick();
                assertTrue(request.getText().contains("Authorization: Bearer real"));
                assertTrue(request.getText().contains("api-key: test-api-secret"));
                assertTrue(((JTextArea) field(app, "response")).getText().contains("Api-Key: test-response-secret"));
                assertEquals("Hide sensitive values", secrets.getText());
                secrets.doClick();
                assertFalse(request.getText().contains("Bearer real"));
                assertTrue(request.getText().contains("api-key: [REDACTED]"));
                assertTrue(((JTextArea) field(app, "response")).getText().contains("Api-Key: [REDACTED]"));
            } catch (Exception error) { throw new RuntimeException(error); }
            finally {
                try { ((ScheduledExecutorService) field(app, "worker")).shutdownNow(); ((JFrame) field(app, "frame")).dispose(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
    }

    @Test public void loadsOnlyAfterTheResponseViewIsOpened() throws Exception {
        Assume.assumeFalse("Requires a graphics environment for an invisible JFrame", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            NetLogDesktop app = new NetLogDesktop();
            try {
                Method ingest = NetLogDesktop.class.getDeclaredMethod("ingest", AdbBridge.Batch.class); ingest.setAccessible(true);
                // A device-local address is never downloaded, so this needs no network.
                ingest.invoke(app, new AdbBridge.Batch(List.of(record("GET", 200, "http://127.0.0.1:1/a.png", "image/png")), "test"));
                JLabel preview = field(app, "preview");
                assertFalse("Nothing is loaded while Overview is open", preview.isVisible());
                assertEquals("", field(app, "previewUrl"));
                JFrame frame = field(app, "frame");
                button(frame.getContentPane(), "Response").doClick();
                assertTrue(preview.isVisible());
                assertTrue(preview.getText(), preview.getText().contains("local to the device"));
            } catch (Exception error) { throw new RuntimeException(error); }
            finally {
                try { ((ScheduledExecutorService) field(app, "worker")).shutdownNow(); ((JFrame) field(app, "frame")).dispose(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
    }

    @SuppressWarnings("unchecked") private static <T> T field(NetLogDesktop app, String name) throws Exception {
        Field field = NetLogDesktop.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(app);
    }
    private static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button && text.equals(button.getText())) return button;
            if (child instanceof Container container) { AbstractButton found = button(container, text); if (found != null) return found; }
        }
        return null;
    }
}
