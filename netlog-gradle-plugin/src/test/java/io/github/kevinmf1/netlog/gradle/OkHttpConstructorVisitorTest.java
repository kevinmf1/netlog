package io.github.kevinmf1.netlog.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import static org.junit.Assert.*;

public class OkHttpConstructorVisitorTest {
    @Test public void okHttp4ClientsGetTheHookBeforeInterceptorsAreCopied() throws Exception { verify("okhttp4"); }
    @Test public void okHttp5ClientsGetTheHookBeforeInterceptorsAreCopied() throws Exception { verify("okhttp5"); }

    @Test(expected = IllegalStateException.class)
    public void failsLoudlyWhenTheBuilderConstructorIsMissing() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, "okhttp3/OkHttpClient", null, "java/lang/Object", null);
        new OkHttpConstructorVisitor(writer).visitEnd();
    }

    private static void verify(String version) throws Exception {
        try (Loader loader = new Loader(System.getProperty(version))) {
            Class<?> client = loader.loadClass("okhttp3.OkHttpClient");
            Class<?> builder = loader.loadClass("okhttp3.OkHttpClient$Builder");
            Object fromDefault = client.getConstructor().newInstance();
            Object fromBuilder = builder.getMethod("build").invoke(builder.getConstructor().newInstance());
            Object cloned = builder.getMethod("build").invoke(client.getMethod("newBuilder").invoke(fromBuilder));
            for (Object instance : new Object[] {fromDefault, fromBuilder, cloned}) {
                assertEquals(version, 1, ((List<?>) client.getMethod("interceptors").invoke(instance)).size());
            }
            assertEquals(version, 3, loader.loadClass("io.github.kevinmf1.netlog.AutoCapture").getField("calls").getInt(null));
        }
    }

    // Child-first loader: the real OkHttp jar plus the test AutoCapture, with only OkHttpClient transformed.
    private static final class Loader extends URLClassLoader {
        Loader(String classpath) throws IOException {
            super(urls(classpath), ClassLoader.getPlatformClassLoader());
        }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals("okhttp3.OkHttpClient")) return super.findClass(name);
            try (InputStream in = getResourceAsStream("okhttp3/OkHttpClient.class")) {
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(in).accept(new OkHttpConstructorVisitor(writer), 0);
                byte[] bytes = writer.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        private static URL[] urls(String classpath) throws IOException {
            assertNotNull("OkHttp classpath system property", classpath);
            List<URL> urls = new ArrayList<>();
            for (String entry : classpath.split(File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
            urls.add(Path.of(System.getProperty("user.dir"), "build/classes/java/test").toUri().toURL());
            return urls.toArray(new URL[0]);
        }
    }
}
