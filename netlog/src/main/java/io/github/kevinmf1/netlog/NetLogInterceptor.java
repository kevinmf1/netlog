package io.github.kevinmf1.netlog;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import okhttp3.*;
import okio.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class NetLogInterceptor implements Interceptor {
    private static final AtomicLong IDS = new AtomicLong();
    private static final int LIMIT = 64 * 1024;
    private final Set<String> secrets = new HashSet<>(Arrays.asList(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"));
    private final CaptureStore store;
    private final boolean enabled;

    public NetLogInterceptor(Context context, String... additionalRedactedHeaders) {
        this.store = CaptureStore.INSTANCE;
        this.enabled = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        for (String name : additionalRedactedHeaders) secrets.add(name.toLowerCase(Locale.ROOT));
        try { LocalBridge.start(context.getApplicationContext()); } catch (Exception ignored) { }
    }

    NetLogInterceptor(CaptureStore store) { this.store = store; this.enabled = true; }

    @Override public Response intercept(Chain chain) throws IOException {
        if (!enabled) return chain.proceed(chain.request());
        Request original = chain.request();
        Entry entry = new Entry(original);
        entry.save();
        Request request = original;
        RequestBody body = original.body();
        if (body != null && !body.isDuplex() && textual(body.contentType()) && original.header("Content-Encoding") == null) {
            request = original.newBuilder().method(original.method(), new RequestBody() {
                @Override public MediaType contentType() { return body.contentType(); }
                @Override public long contentLength() throws IOException { return body.contentLength(); }
                @Override public boolean isOneShot() { return body.isOneShot(); }
                @Override public void writeTo(BufferedSink sink) throws IOException {
                    Buffer copy = new Buffer();
                    long[] count = {0};
                    BufferedSink tee = Okio.buffer(new ForwardingSink(sink) {
                        @Override public void write(Buffer source, long bytes) throws IOException {
                            long keep = Math.min(bytes, LIMIT - copy.size());
                            if (keep > 0) source.copyTo(copy, 0, keep);
                            count[0] += bytes;
                            super.write(source, bytes);
                        }
                    });
                    try { body.writeTo(tee); tee.emit(); }
                    finally {
                        entry.set("requestBody", decode(copy, body.contentType()));
                        entry.set("requestTruncated", count[0] > LIMIT);
                        entry.save();
                    }
                }
            }).build();
        } else if (body != null) entry.set("requestBody", "[Body omitted: binary, encoded, or duplex]");

        Response response;
        try { response = chain.proceed(request); }
        catch (IOException | RuntimeException error) {
            entry.set("error", error.getClass().getSimpleName());
            entry.set("state", "Failed"); entry.finish(); throw error;
        }
        entry.set("status", response.code());
        entry.set("responseHeaders", headers(response.headers()));
        entry.set("finalUrl", safeUrl(response.request().url()));
        entry.set("headersMs", (System.nanoTime() - entry.start) / 1_000_000);
        ResponseBody responseBody = response.body();
        if (responseBody == null) { entry.set("state", "Complete"); entry.finish(); return response; }
        boolean capture = textual(responseBody.contentType()) && response.header("Content-Encoding") == null;
        if (!capture) entry.set("responseBody", "[Body omitted: binary or encoded]");
        entry.set("state", "Reading body"); entry.save();
        BufferedSource source = Okio.buffer(new ForwardingSource(responseBody.source()) {
            final Buffer copy = new Buffer();
            long count;
            boolean finished;
            private void done(String state) {
                if (finished) return;
                finished = true;
                if (capture) entry.set("responseBody", decode(copy, responseBody.contentType()));
                entry.set("responseBytes", count);
                entry.set("responseTruncated", capture && count > LIMIT);
                entry.set("state", state); entry.finish();
            }
            @Override public long read(Buffer sink, long bytes) throws IOException {
                long offset = sink.size();
                try {
                    long read = super.read(sink, bytes);
                    if (read == -1) done("Complete");
                    else {
                        count += read;
                        long keep = Math.min(read, LIMIT - copy.size());
                        if (capture && keep > 0) sink.copyTo(copy, offset, keep);
                        if (responseBody.contentLength() >= 0 && count == responseBody.contentLength()) done("Complete");
                    }
                    return read;
                } catch (IOException error) {
                    entry.set("error", error.getClass().getSimpleName()); done("Read failed"); throw error;
                }
            }
            @Override public void close() throws IOException {
                try { super.close(); } finally { done("Closed early"); }
            }
        });
        return response.newBuilder().body(new ResponseBody() {
            @Override public MediaType contentType() { return responseBody.contentType(); }
            @Override public long contentLength() { return responseBody.contentLength(); }
            @Override public BufferedSource source() { return source; }
        }).build();
    }

    private static boolean textual(MediaType type) {
        if (type == null) return false;
        String subtype = type.subtype();
        return type.type().equals("text") || subtype.contains("json") || subtype.contains("xml") || subtype.equals("x-www-form-urlencoded");
    }

    private static String decode(Buffer buffer, MediaType type) {
        try { return buffer.clone().readString(type == null ? StandardCharsets.UTF_8 : type.charset(StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return "[Body could not be decoded]"; }
    }

    private JSONArray headers(Headers headers) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);
            result.put(new JSONArray().put(name).put(secrets.contains(name.toLowerCase(Locale.ROOT)) ? "[REDACTED]" : headers.value(i)));
        }
        return result;
    }

    private static String safeUrl(HttpUrl url) {
        HttpUrl.Builder builder = url.newBuilder().username("").password("");
        for (String key : url.queryParameterNames()) {
            String name = key.toLowerCase(Locale.ROOT);
            if (name.contains("token") || name.contains("secret") || name.contains("password") || name.equals("api_key") || name.equals("apikey"))
                builder.setQueryParameter(key, "[REDACTED]");
        }
        return builder.build().toString();
    }

    private final class Entry {
        final long id = IDS.incrementAndGet();
        final long start = System.nanoTime();
        final JSONObject json = new JSONObject();
        Entry(Request request) {
            set("type", "request"); set("id", id); set("time", System.currentTimeMillis());
            set("method", request.method()); set("url", safeUrl(request.url()));
            set("requestHeaders", headers(request.headers())); set("state", "Pending");
        }
        synchronized void set(String key, Object value) { try { json.put(key, value); } catch (Exception ignored) { } }
        synchronized void save() { store.put(id, json); }
        synchronized void finish() { set("durationMs", (System.nanoTime() - start) / 1_000_000); save(); }
    }
}
