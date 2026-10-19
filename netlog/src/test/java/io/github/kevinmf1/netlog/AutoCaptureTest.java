package io.github.kevinmf1.netlog;

import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoCaptureTest {
    @Test public void attachesOnceForReusedAndClonedBuilders() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        AutoCapture.attach(builder); AutoCapture.attach(builder);
        assertEquals(1, builder.interceptors().size());
        OkHttpClient.Builder cloned = builder.build().newBuilder();
        AutoCapture.attach(cloned);
        assertEquals(1, cloned.interceptors().size());
    }

    @Test public void staysAfterInterceptorsAddedToADerivedClient() {
        OkHttpClient.Builder base = new OkHttpClient.Builder();
        AutoCapture.attach(base);
        OkHttpClient.Builder derived = base.build().newBuilder().addInterceptor(chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "token").build()));
        AutoCapture.attach(derived);
        assertEquals(2, derived.interceptors().size());
        assertSame("Capture must see headers added by the app's own interceptors", base.interceptors().get(0), derived.interceptors().get(1));
    }

    @Test public void respectsAnExplicitCaptureInterceptor() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addInterceptor(new NetLogInterceptor(new CaptureStore()));
        AutoCapture.attach(builder);
        assertEquals(1, builder.interceptors().size());
    }
}
