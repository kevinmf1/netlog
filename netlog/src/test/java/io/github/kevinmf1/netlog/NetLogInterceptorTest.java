package io.github.kevinmf1.netlog;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import okio.BufferedSink;
import org.json.*;
import org.junit.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NetLogInterceptorTest {
    private MockWebServer server;
    private CaptureStore store;
    private OkHttpClient client;
    @Before public void setup() throws IOException {
        server = new MockWebServer(); server.start(); store = new CaptureStore();
        client = new OkHttpClient.Builder().addInterceptor(new NetLogInterceptor(store)).build();
    }
    @After public void close() throws IOException { server.shutdown(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
    private JSONObject last() throws Exception { List<String> rows = store.since(0); return new JSONObject(rows.get(rows.size() - 2)); }

    @Test public void preservesResponseAndSendsRawValuesExceptNamedHeaders() throws Exception {
        OkHttpClient named = new OkHttpClient.Builder().addInterceptor(new NetLogInterceptor(store, "X-Internal")).build();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setHeader("Set-Cookie", "session=raw").setBody("{\"ok\":true}"));
        try (Response response = named.newCall(new Request.Builder().url(server.url("/check?token=raw-query"))
                .header("Authorization", "Bearer raw-token").header("X-Internal", "never-sent").build()).execute()) {
            assertEquals("{\"ok\":true}", response.body().string());
        }
        JSONObject record = last(); assertEquals(200, record.getInt("status")); assertEquals("Complete", record.getString("state"));
        // The desktop masks these for display; the device sends them so they can be revealed and replayed.
        assertTrue(record.getString("url").endsWith("token=raw-query"));
        assertTrue(record.toString().contains("Bearer raw-token")); assertTrue(record.toString().contains("session=raw"));
        assertFalse("Headers named by the app are never sent", record.toString().contains("never-sent"));
        assertEquals("{\"ok\":true}", record.getString("responseBody"));
    }

    @Test public void sendsOneShotBodyExactlyOnce() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        server.enqueue(new MockResponse().setBody("ok"));
        RequestBody body = new RequestBody() {
            public MediaType contentType() { return MediaType.get("application/json"); }
            public boolean isOneShot() { return true; }
            public void writeTo(BufferedSink sink) throws IOException { writes.incrementAndGet(); sink.writeUtf8("{\"message\":\"hello\"}"); }
        };
        try (Response response = client.newCall(new Request.Builder().url(server.url("/post")).post(body).build()).execute()) { response.body().string(); }
        assertEquals(1, writes.get()); assertEquals("{\"message\":\"hello\"}", server.takeRequest().getBody().readUtf8());
        assertEquals("{\"message\":\"hello\"}", last().getString("requestBody"));
    }

    @Test public void boundsCaptureWithoutTruncatingApplicationBody() throws Exception {
        String payload = new String(new char[100_000]).replace('\0', 'x');
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody(payload));
        try (Response response = client.newCall(new Request.Builder().url(server.url("/")).build()).execute()) { assertEquals(payload, response.body().string()); }
        assertEquals(65536, last().getString("responseBody").length()); assertTrue(last().getBoolean("responseTruncated"));
    }

    @Test public void doesNotReadBodyBeforeApplicationDoes() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody("slow").setBodyDelay(2, TimeUnit.SECONDS));
        try (Response response = client.newCall(new Request.Builder().url(server.url("/slow")).build()).execute()) {
            assertEquals("Reading body", last().getString("state")); assertFalse(last().has("responseBody"));
        }
        assertEquals("Closed early", last().getString("state"));
    }

    @Test public void recordsFailureAndKeepsOriginalException() throws Exception {
        OkHttpClient broken = client.newBuilder().addInterceptor(chain -> { throw new IOException("private URL or token"); }).build();
        try { broken.newCall(new Request.Builder().url(server.url("/fail")).build()).execute(); fail(); }
        catch (IOException error) { assertEquals("private URL or token", error.getMessage()); }
        assertEquals("Failed", last().getString("state")); assertEquals("IOException", last().getString("error")); assertFalse(last().toString().contains("private URL"));
    }

    @Test public void omitsBinaryBodyButKeepsBytesIntact() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("binary"));
        try (Response response = client.newCall(new Request.Builder().url(server.url("/")).build()).execute()) { assertEquals("binary", response.body().string()); }
        assertTrue(last().getString("responseBody").startsWith("[Body omitted")); assertEquals(6, last().getInt("responseBytes"));
    }

    @Test public void ringAndRevisionCursorRemainBounded() throws Exception {
        for (int i = 1; i <= 250; i++) store.put(i, new JSONObject().put("id", i));
        assertEquals(201, store.since(0).size()); assertEquals(1, store.since(250).size());
        store.put(250, new JSONObject().put("id", 250).put("status", 200));
        assertEquals(2, store.since(250).size());
    }
}
