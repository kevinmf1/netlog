package io.github.kevinmf1.netlog;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public final class AutoCapture {
    private static volatile NetLogInterceptor capture;
    private static final Interceptor AUTOMATIC = new Interceptor() {
        @Override public Response intercept(Chain chain) throws IOException {
            NetLogInterceptor current = capture;
            return current == null ? chain.proceed(chain.request()) : current.intercept(chain);
        }
    };
    private AutoCapture() { }

    static void initialize(Context context) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) capture = new NetLogInterceptor(context);
    }

    public static void attach(OkHttpClient.Builder builder) {
        if (builder == null) return;
        for (Interceptor interceptor : builder.interceptors()) {
            if (interceptor instanceof NetLogInterceptor) return;
        }
        // newBuilder() copies capture from the base client; move it after interceptors added since, such as auth.
        builder.interceptors().remove(AUTOMATIC);
        builder.addInterceptor(AUTOMATIC);
    }
}
