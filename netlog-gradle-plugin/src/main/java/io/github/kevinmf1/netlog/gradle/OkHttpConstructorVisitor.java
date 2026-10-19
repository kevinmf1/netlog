package io.github.kevinmf1.netlog.gradle;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class OkHttpConstructorVisitor extends ClassVisitor {
    private boolean matched;
    OkHttpConstructorVisitor(ClassVisitor next) { super(Opcodes.ASM9, next); }
    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!name.equals("<init>") || !descriptor.equals("(Lokhttp3/OkHttpClient$Builder;)V")) return next;
        matched = true;
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override public void visitCode() {
                super.visitCode();
                // Only the builder is used here; the client has not copied its interceptor list yet.
                super.visitVarInsn(Opcodes.ALOAD, 1);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "io/github/kevinmf1/netlog/AutoCapture", "attach", "(Lokhttp3/OkHttpClient$Builder;)V", false);
            }
        };
    }
    @Override public void visitEnd() {
        if (!matched) throw new IllegalStateException("NetLog: unsupported OkHttpClient constructor. Automatic capture was not installed.");
        super.visitEnd();
    }
}
