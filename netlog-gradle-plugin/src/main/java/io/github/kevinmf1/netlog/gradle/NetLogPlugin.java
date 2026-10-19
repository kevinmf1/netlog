package io.github.kevinmf1.netlog.gradle;

import com.android.build.api.instrumentation.FramesComputationMode;
import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.variant.ApplicationVariantBuilder;
import java.util.HashSet;
import java.util.Set;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public final class NetLogPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        project.getPluginManager().withPlugin("com.android.application", applied -> {
            ApplicationAndroidComponentsExtension android = project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            Set<String> debuggable = new HashSet<>();
            android.beforeVariants(android.selector().all(), (Action<ApplicationVariantBuilder>) variant -> {
                if (variant.getDebuggable()) debuggable.add(variant.getName());
            });
            android.onVariants(android.selector().all(), (Action<ApplicationVariant>) variant -> {
                if (!debuggable.contains(variant.getName())) return;
                // Inside the NetLog repository the sample uses the runtime source, not a possibly stale Maven Local copy.
                Project local = project.findProject(":netlog");
                project.getDependencies().add(variant.getName() + "RuntimeOnly", local != null ? local : "io.github.kevinmf1:netlog:0.1.0-SNAPSHOT");
                variant.getInstrumentation().transformClassesWith(OkHttpVisitorFactory.class, InstrumentationScope.ALL, parameters -> kotlin.Unit.INSTANCE);
                variant.getInstrumentation().setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS);
            });
        });
    }
}
