package io.github.kevinmf1.netlog;

import android.content.Context;
import okhttp3.Interceptor;
import okhttp3.Response;
import java.io.IOException;

public final class NetLogInterceptor implements Interceptor {
    public NetLogInterceptor(Context context, String... additionalRedactedHeaders) { }
    @Override public Response intercept(Chain chain) throws IOException { return chain.proceed(chain.request()); }
}
