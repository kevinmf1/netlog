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

    @Test public void respectsAnExplicitCaptureInterceptor() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addInterceptor(new NetLogInterceptor(new CaptureStore()));
        AutoCapture.attach(builder);
        assertEquals(1, builder.interceptors().size());
    }
}
