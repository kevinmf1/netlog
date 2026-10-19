package io.github.kevinmf1.netlog.gradle;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;

public abstract class OkHttpVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override public boolean isInstrumentable(ClassData data) { return data.getClassName().equals("okhttp3.OkHttpClient"); }
    @Override public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) { return new OkHttpConstructorVisitor(next); }
}
