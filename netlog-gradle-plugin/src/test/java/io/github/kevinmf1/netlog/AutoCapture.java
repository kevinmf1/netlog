package io.github.kevinmf1.netlog;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

// Test double for the Android runtime hook; the real duplicate rules are covered by AutoCaptureTest in :netlog.
public final class AutoCapture {
    public static int calls;
    public static final Interceptor MARKER = chain -> chain.proceed(chain.request());

    public static void attach(OkHttpClient.Builder builder) {
        calls++;
        if (!builder.interceptors().contains(MARKER)) builder.addInterceptor(MARKER);
    }
}
