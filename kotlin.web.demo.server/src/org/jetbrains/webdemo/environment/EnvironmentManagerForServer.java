package org.jetbrains.webdemo.environment;

import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.intellij.codeInsight.ContainerProvider;
import com.intellij.codeInsight.NullableNotNullManager;
import com.intellij.codeInsight.runner.JavaMainMethodProvider;
import com.intellij.core.CoreApplicationEnvironment;
import com.intellij.mock.MockProject;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.extensions.ExtensionsArea;
import com.intellij.openapi.fileTypes.ContentBasedFileSubstitutor;
import com.intellij.openapi.fileTypes.FileTypeExtensionPoint;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.psi.FileContextProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.compiled.ClassFileDecompilers;
import com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy;
import com.intellij.psi.impl.compiled.ClsStubBuilderFactory;
import com.intellij.psi.meta.MetaDataContributor;
import com.intellij.psi.stubs.BinaryFileStubBuilders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages;
import org.jetbrains.kotlin.extensions.ProjectExtensionDescriptor;
import org.jetbrains.kotlin.js.analyze.SuppressUnusedParameterForJsNative;
import org.jetbrains.kotlin.js.resolve.diagnostics.DefaultErrorMessagesJs;
import org.jetbrains.kotlin.load.kotlin.nativeDeclarations.SuppressNoBodyErrorsForNativeDeclarations;
import org.jetbrains.kotlin.resolve.AnalyzerScriptParameter;
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticsWithSuppression;
import org.jetbrains.kotlin.resolve.jvm.diagnostics.DefaultErrorMessagesJvm;
import org.jetbrains.kotlin.utils.PathUtil;
import org.jetbrains.webdemo.ErrorWriter;
import org.jetbrains.webdemo.server.ApplicationSettings;

import java.io.File;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.kotlin.cli.jvm.config.ConfigPackage.addJvmClasspathRoots;

public class EnvironmentManagerForServer extends EnvironmentManager {

    public EnvironmentManagerForServer() {
        initializeKotlinRuntime();
        initializeKotlinReflect();
    }

    @NotNull
    public KotlinCoreEnvironment createEnvironment() {
        K2JVMCompilerArguments arguments = new K2JVMCompilerArguments();
        CompilerConfiguration configuration = new CompilerConfiguration();
        addJvmClasspathRoots(configuration, getClasspath(arguments));
        configuration.addAll(JVMConfigurationKeys.ANNOTATIONS_PATH_KEY, getAnnotationsPath());

        configuration.put(JVMConfigurationKeys.SCRIPT_PARAMETERS, Collections.<AnalyzerScriptParameter>emptyList());

        configuration.put(JVMConfigurationKeys.DISABLE_PARAM_ASSERTIONS, arguments.noParamAssertions);
        configuration.put(JVMConfigurationKeys.DISABLE_CALL_ASSERTIONS, arguments.noCallAssertions);

        KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForTests(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES);

        ((MockProject) environment.getProject()).registerService(NullableNotNullManager.class, new NullableNotNullManager() {
            @Override
            public boolean isNullable(@NotNull PsiModifierListOwner owner, boolean checkBases) {
                return false;
            }

            @Override
            public boolean isNotNull(@NotNull PsiModifierListOwner owner, boolean checkBases) {
                return true;
            }

            @Override
            protected boolean hasHardcodedContracts(PsiElement element) {
                return false;
            }
        });

        Extensions.getRootArea().getExtensionPoint(DefaultErrorMessages.Extension.EP_NAME).registerExtension(new DefaultErrorMessagesJvm());
        Extensions.getRootArea().getExtensionPoint(DefaultErrorMessages.Extension.EP_NAME).registerExtension(new DefaultErrorMessagesJs());
        Extensions.getRootArea().getExtensionPoint(DiagnosticsWithSuppression.SuppressStringProvider.EP_NAME).registerExtension(new SuppressNoBodyErrorsForNativeDeclarations());
        Extensions.getRootArea().getExtensionPoint(DiagnosticsWithSuppression.SuppressStringProvider.EP_NAME).registerExtension(new SuppressUnusedParameterForJsNative());

        registerExtensionPoints(Extensions.getRootArea());

        registry = FileTypeRegistry.ourInstanceGetter;
        return environment;
    }

    private void registerExtensionPoints(ExtensionsArea area) {
        CoreApplicationEnvironment.registerExtensionPoint(area, ContentBasedFileSubstitutor.EP_NAME, ContentBasedFileSubstitutor.class);
        CoreApplicationEnvironment.registerExtensionPoint(area, BinaryFileStubBuilders.EP_NAME, FileTypeExtensionPoint.class);
        CoreApplicationEnvironment.registerExtensionPoint(area, FileContextProvider.EP_NAME, FileContextProvider.class);

        CoreApplicationEnvironment.registerExtensionPoint(area, MetaDataContributor.EP_NAME, MetaDataContributor.class);
        CoreApplicationEnvironment.registerExtensionPoint(area, ClsStubBuilderFactory.EP_NAME, ClsStubBuilderFactory.class);
        CoreApplicationEnvironment.registerExtensionPoint(area, PsiAugmentProvider.EP_NAME, PsiAugmentProvider.class);
        CoreApplicationEnvironment.registerExtensionPoint(area, JavaMainMethodProvider.EP_NAME, JavaMainMethodProvider.class);

        CoreApplicationEnvironment.registerExtensionPoint(area, ContainerProvider.EP_NAME, ContainerProvider.class);
        CoreApplicationEnvironment.registerExtensionPoint(area, ClsCustomNavigationPolicy.EP_NAME, ClsCustomNavigationPolicy.class);
        CoreApplicationEnvironment.registerExtensionPoint(area, ClassFileDecompilers.EP_NAME, ClassFileDecompilers.Decompiler.class);
    }

    private static File initializeKotlinReflect() {
        final File unpackedRuntimePath = getUnpackedRuntimePath("kotlin/reflect/jvm/internal/KClassImpl.class");
        if (unpackedRuntimePath != null) {
            ApplicationSettings.KOTLIN_REFLECT = unpackedRuntimePath.getAbsolutePath();
            ErrorWriter.writeInfoToConsole("Kotlin Reflection library founded at " + ApplicationSettings.KOTLIN_REFLECT);
            return unpackedRuntimePath;
        } else {
            final File runtimeJarPath = getRuntimeJarPath("kotlin/reflect/jvm/internal/KClassImpl.class");
            if (runtimeJarPath != null && runtimeJarPath.exists()) {
                ApplicationSettings.KOTLIN_REFLECT = runtimeJarPath.getAbsolutePath();
                ErrorWriter.writeInfoToConsole("Kotlin Reflection library founded at " + ApplicationSettings.KOTLIN_REFLECT);
                return runtimeJarPath;
            }
        }
        return null;
    }

    @Nullable
    private static File initializeKotlinRuntime() {
        final File unpackedRuntimePath = getUnpackedRuntimePath("kotlin/jvm/internal/Intrinsics.class");
        if (unpackedRuntimePath != null) {
            ApplicationSettings.KOTLIN_RUNTIME = unpackedRuntimePath.getAbsolutePath();
            ErrorWriter.writeInfoToConsole("Kotlin Runtime library founded at " + ApplicationSettings.KOTLIN_RUNTIME);
            return unpackedRuntimePath;
        } else {
            final File runtimeJarPath = getRuntimeJarPath("kotlin/jvm/internal/Intrinsics.class");
            if (runtimeJarPath != null && runtimeJarPath.exists()) {
                ApplicationSettings.KOTLIN_RUNTIME = runtimeJarPath.getAbsolutePath();
                ErrorWriter.writeInfoToConsole("Kotlin Runtime library founded at " + ApplicationSettings.KOTLIN_RUNTIME);
                return runtimeJarPath;
            }
        }
        return null;
    }

    @Nullable
    private static File getUnpackedRuntimePath(String className) {
        URL url = K2JVMCompiler.class.getClassLoader().getResource(className);
        if (url != null && url.getProtocol().equals("file")) {
            return new File(url.getPath()).getParentFile().getParentFile();
        }
        return null;
    }

    @Nullable
    private static File getRuntimeJarPath(String className) {
        URL url = K2JVMCompiler.class.getClassLoader().getResource(className);
        if (url != null && url.getProtocol().equals("jar")) {
            String path = url.getPath();
            return new File(path.substring(path.indexOf(":") + 1, path.indexOf("!/")));
        }
        return null;
    }

    @NotNull
    private static List<File> getClasspath(@NotNull K2JVMCompilerArguments arguments) {
        List<File> classpath = Lists.newArrayList();
        classpath.addAll(PathUtil.getJdkClassesRoots());

        Collection<File> files = Collections2.filter(PathUtil.getJdkClassesRoots(), new Predicate<File>() {
            @Override
            public boolean apply(@NotNull File file) {
                return file.getName().equals("rt.jar") || file.getName().endsWith("classes.jar");
            }
        });

        ApplicationSettings.JAVA_HOME = files.iterator().next().getParentFile().getParentFile().getParentFile().getAbsolutePath();

        classpath.add(new File(ApplicationSettings.KOTLIN_RUNTIME));
        classpath.add(new File(ApplicationSettings.KOTLIN_REFLECT));
        if (arguments.classpath != null) {
            for (String element : Splitter.on(File.pathSeparatorChar).split(arguments.classpath)) {
                classpath.add(new File(element));
            }
        }
        return classpath;
    }

    @NotNull
    private List<File> getAnnotationsPath() {
        List<File> annotationsPath = Lists.newArrayList();
        annotationsPath.add(PathUtil.getKotlinPathsForCompiler().getJdkAnnotationsPath());
        return annotationsPath;
    }
}
