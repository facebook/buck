/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidAarDescription;
import com.facebook.buck.android.AndroidAppModularityDescription;
import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidBuildConfigDescription;
import com.facebook.buck.android.AndroidInstrumentationApkDescription;
import com.facebook.buck.android.AndroidInstrumentationTestDescription;
import com.facebook.buck.android.AndroidLibraryCompilerFactory;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidPrebuiltAarDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.ApkGenruleDescription;
import com.facebook.buck.android.DefaultAndroidLibraryCompilerFactory;
import com.facebook.buck.android.DxConfig;
import com.facebook.buck.android.GenAidlDescription;
import com.facebook.buck.android.NdkLibraryDescription;
import com.facebook.buck.android.PrebuiltNativeLibraryDescription;
import com.facebook.buck.android.ProGuardConfig;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.android.SmartDexingStep;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.ApplePackageDescription;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.CodeSignIdentityStoreFactory;
import com.facebook.buck.apple.PrebuiltAppleFrameworkDescription;
import com.facebook.buck.apple.ProvisioningProfileStore;
import com.facebook.buck.apple.SceneKitAssetsDescription;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPrecompiledHeaderDescription;
import com.facebook.buck.cxx.CxxTestDescription;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescription;
import com.facebook.buck.cxx.PrebuiltCxxLibraryGroupDescription;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.graphql.GraphqlLibraryDescription;
import com.facebook.buck.gwt.GwtBinaryDescription;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.halide.HalideLibraryDescription;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.js.JsBundleDescription;
import com.facebook.buck.js.JsBundleGenruleDescription;
import com.facebook.buck.js.JsLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyBuckConfig;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.java.JavaAnnotationProcessorDescription;
import com.facebook.buck.jvm.java.JavaBinaryDescription;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.KeystoreDescription;
import com.facebook.buck.jvm.kotlin.KotlinBuckConfig;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;
import com.facebook.buck.jvm.kotlin.KotlinTestDescription;
import com.facebook.buck.jvm.scala.ScalaBuckConfig;
import com.facebook.buck.jvm.scala.ScalaLibraryDescription;
import com.facebook.buck.jvm.scala.ScalaTestDescription;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.ocaml.OcamlBinaryDescription;
import com.facebook.buck.ocaml.OcamlBuckConfig;
import com.facebook.buck.ocaml.OcamlLibraryDescription;
import com.facebook.buck.ocaml.PrebuiltOcamlLibraryDescription;
import com.facebook.buck.python.CxxPythonExtensionDescription;
import com.facebook.buck.python.PrebuiltPythonLibraryDescription;
import com.facebook.buck.python.PythonBinaryDescription;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.python.PythonTestDescription;
import com.facebook.buck.rules.keys.RuleKeyConfiguration;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.shell.CommandAliasDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.ShBinaryDescription;
import com.facebook.buck.shell.ShTestDescription;
import com.facebook.buck.shell.WorkerToolDescription;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionedAliasDescription;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import org.immutables.value.Value;
import org.pf4j.PluginManager;

/** A registry of all the build rules types understood by Buck. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractKnownBuildRuleTypes {

  private static final Logger LOG = Logger.get(AbstractKnownBuildRuleTypes.class);

  /** @return all the underlying {@link Description}s. */
  @Value.Parameter
  abstract ImmutableList<Description<?>> getDescriptions();

  // Verify that there are no duplicate rule types being defined.
  @Value.Check
  protected void check() {
    Set<BuildRuleType> types = new HashSet<>();
    for (Description<?> description : getDescriptions()) {
      BuildRuleType type = Description.getBuildRuleType(description);
      if (!types.add(Description.getBuildRuleType(description))) {
        throw new IllegalStateException(String.format("multiple descriptions with type %s", type));
      }
    }
  }

  @Value.Lazy
  protected ImmutableMap<BuildRuleType, Description<?>> getDescriptionsByType() {
    return getDescriptions()
        .stream()
        .collect(ImmutableMap.toImmutableMap(Description::getBuildRuleType, d -> d));
  }

  @Value.Lazy
  protected ImmutableMap<String, BuildRuleType> getTypesByName() {
    return getDescriptions()
        .stream()
        .map(Description::getBuildRuleType)
        .collect(ImmutableMap.toImmutableMap(BuildRuleType::getName, t -> t));
  }

  public BuildRuleType getBuildRuleType(String named) {
    BuildRuleType type = getTypesByName().get(named);
    if (type == null) {
      throw new HumanReadableException("Unable to find build rule type: " + named);
    }
    return type;
  }

  public Description<?> getDescription(BuildRuleType buildRuleType) {
    Description<?> description = getDescriptionsByType().get(buildRuleType);
    if (description == null) {
      throw new HumanReadableException(
          "Unable to find description for build rule type: " + buildRuleType);
    }
    return description;
  }

  static KnownBuildRuleTypes createInstance(
      BuckConfig config,
      ProcessExecutor processExecutor,
      ToolchainProvider toolchainProvider,
      PluginManager pluginManager,
      RuleKeyConfiguration ruleKeyConfiguration,
      SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory)
      throws InterruptedException, IOException {

    SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(config);

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);

    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);

    // Build up the final list of C/C++ platforms.
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProviderFactory.getCxxPlatforms();

    // Get the default target platform from config.
    CxxPlatform defaultCxxPlatform = cxxPlatformsProviderFactory.getDefaultCxxPlatform();

    HalideBuckConfig halideBuckConfig = new HalideBuckConfig(config);

    ProGuardConfig proGuardConfig = new ProGuardConfig(config);

    DxConfig dxConfig = new DxConfig(config);

    ExecutableFinder executableFinder = new ExecutableFinder();

    PythonBuckConfig pyConfig = new PythonBuckConfig(config, executableFinder);
    PythonBinaryDescription pythonBinaryDescription =
        new PythonBinaryDescription(
            toolchainProvider,
            ruleKeyConfiguration,
            pyConfig,
            cxxBuckConfig,
            defaultCxxPlatform,
            cxxPlatforms);

    KnownBuildRuleTypes.Builder builder = KnownBuildRuleTypes.builder();

    JavaBuckConfig javaConfig = config.getView(JavaBuckConfig.class);
    JavacOptions defaultJavacOptions = javaConfig.getDefaultJavacOptions();
    JavaOptions defaultJavaOptions = javaConfig.getDefaultJavaOptions();
    JavaOptions defaultJavaOptionsForTests = javaConfig.getDefaultJavaOptionsForTests();
    CxxPlatform defaultJavaCxxPlatform =
        javaConfig
            .getDefaultCxxPlatform()
            .map(InternalFlavor::of)
            .map(cxxPlatforms::getValue)
            .orElse(defaultCxxPlatform);

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(config);

    ScalaBuckConfig scalaConfig = new ScalaBuckConfig(config);

    InferBuckConfig inferBuckConfig = new InferBuckConfig(config);

    CxxBinaryDescription cxxBinaryDescription =
        new CxxBinaryDescription(
            cxxBuckConfig, inferBuckConfig, defaultCxxPlatform.getFlavor(), cxxPlatforms);

    CxxLibraryDescription cxxLibraryDescription =
        new CxxLibraryDescription(
            cxxBuckConfig, defaultCxxPlatform.getFlavor(), inferBuckConfig, cxxPlatforms);

    SwiftLibraryDescription swiftLibraryDescription =
        new SwiftLibraryDescription(
            toolchainProvider, cxxBuckConfig, swiftBuckConfig, cxxPlatforms);
    builder.addDescriptions(swiftLibraryDescription);

    AppleConfig appleConfig = config.getView(AppleConfig.class);
    CodeSignIdentityStore codeSignIdentityStore =
        CodeSignIdentityStoreFactory.fromSystem(
            processExecutor, appleConfig.getCodeSignIdentitiesCommand());
    ProvisioningProfileStore provisioningProfileStore =
        ProvisioningProfileStore.fromSearchPath(
            processExecutor,
            appleConfig.getProvisioningProfileReadCommand(),
            appleConfig.getProvisioningProfileSearchPath());

    AppleLibraryDescription appleLibraryDescription =
        new AppleLibraryDescription(
            toolchainProvider,
            cxxLibraryDescription,
            swiftLibraryDescription,
            defaultCxxPlatform.getFlavor(),
            codeSignIdentityStore,
            provisioningProfileStore,
            appleConfig,
            swiftBuckConfig);
    builder.addDescriptions(appleLibraryDescription);
    PrebuiltAppleFrameworkDescription appleFrameworkDescription =
        new PrebuiltAppleFrameworkDescription(toolchainProvider, cxxBuckConfig);
    builder.addDescriptions(appleFrameworkDescription);

    AppleBinaryDescription appleBinaryDescription =
        new AppleBinaryDescription(
            toolchainProvider,
            cxxBinaryDescription,
            swiftLibraryDescription,
            codeSignIdentityStore,
            provisioningProfileStore,
            appleConfig);
    builder.addDescriptions(appleBinaryDescription);

    if (javaConfig.getDxThreadCount().isPresent()) {
      LOG.warn("java.dx_threads has been deprecated. Use dx.max_threads instead");
    }

    // Create an executor service exclusively for the smart dexing step.
    ListeningExecutorService dxExecutorService =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                dxConfig
                    .getDxMaxThreadCount()
                    .orElse(
                        javaConfig
                            .getDxThreadCount()
                            .orElse(SmartDexingStep.determineOptimalThreadCount())),
                new CommandThreadFactory("SmartDexing")));

    AndroidLibraryCompilerFactory defaultAndroidCompilerFactory =
        new DefaultAndroidLibraryCompilerFactory(
            toolchainProvider, javaConfig, scalaConfig, kotlinBuckConfig);

    SandboxExecutionStrategy sandboxExecutionStrategy =
        sandboxExecutionStrategyFactory.create(processExecutor, config);

    builder.addDescriptions(
        new AndroidAarDescription(
            toolchainProvider,
            new AndroidManifestDescription(),
            cxxBuckConfig,
            javaConfig,
            defaultJavacOptions));
    builder.addDescriptions(new AndroidAppModularityDescription());
    builder.addDescriptions(
        new AndroidBinaryDescription(
            toolchainProvider,
            javaConfig,
            defaultJavaOptions,
            defaultJavacOptions,
            proGuardConfig,
            dxExecutorService,
            config,
            cxxBuckConfig,
            dxConfig));
    builder.addDescriptions(new AndroidBuildConfigDescription(javaConfig, defaultJavacOptions));
    builder.addDescriptions(
        new AndroidInstrumentationApkDescription(
            toolchainProvider,
            javaConfig,
            proGuardConfig,
            defaultJavacOptions,
            dxExecutorService,
            cxxBuckConfig,
            dxConfig));
    builder.addDescriptions(
        new AndroidInstrumentationTestDescription(config, toolchainProvider, defaultJavaOptions));
    builder.addDescriptions(
        new AndroidLibraryDescription(
            javaConfig, defaultJavacOptions, defaultAndroidCompilerFactory));
    builder.addDescriptions(new AndroidManifestDescription());
    builder.addDescriptions(
        new AndroidPrebuiltAarDescription(toolchainProvider, javaConfig, defaultJavacOptions));
    builder.addDescriptions(
        new AndroidResourceDescription(
            toolchainProvider, config.isGrayscaleImageProcessingEnabled()));
    builder.addDescriptions(new ApkGenruleDescription(toolchainProvider, sandboxExecutionStrategy));
    builder.addDescriptions(
        new ApplePackageDescription(
            toolchainProvider,
            sandboxExecutionStrategy,
            appleConfig,
            defaultCxxPlatform.getFlavor()));
    AppleBundleDescription appleBundleDescription =
        new AppleBundleDescription(
            toolchainProvider,
            appleBinaryDescription,
            appleLibraryDescription,
            cxxPlatforms,
            defaultCxxPlatform.getFlavor(),
            codeSignIdentityStore,
            provisioningProfileStore,
            appleConfig);
    builder.addDescriptions(appleBundleDescription);
    builder.addDescriptions(
        new AppleTestDescription(
            toolchainProvider,
            appleConfig,
            appleLibraryDescription,
            cxxPlatforms,
            defaultCxxPlatform.getFlavor(),
            codeSignIdentityStore,
            provisioningProfileStore,
            appleConfig.getAppleDeveloperDirectorySupplierForTests(processExecutor)));
    builder.addDescriptions(new CommandAliasDescription(Platform.detect()));
    builder.addDescriptions(cxxBinaryDescription);
    builder.addDescriptions(cxxLibraryDescription);
    builder.addDescriptions(
        new CxxGenruleDescription(
            cxxBuckConfig, toolchainProvider, sandboxExecutionStrategy, cxxPlatforms));
    builder.addDescriptions(
        new CxxPythonExtensionDescription(toolchainProvider, cxxBuckConfig, cxxPlatforms));
    builder.addDescriptions(
        new CxxTestDescription(cxxBuckConfig, defaultCxxPlatform.getFlavor(), cxxPlatforms));
    builder.addDescriptions(new ExportFileDescription());
    builder.addDescriptions(
        new GenruleDescription(toolchainProvider, config, sandboxExecutionStrategy));
    builder.addDescriptions(new GenAidlDescription(toolchainProvider));
    builder.addDescriptions(new GraphqlLibraryDescription());
    GroovyBuckConfig groovyBuckConfig = new GroovyBuckConfig(config);
    builder.addDescriptions(
        new GroovyLibraryDescription(groovyBuckConfig, javaConfig, defaultJavacOptions));
    builder.addDescriptions(
        new GroovyTestDescription(
            groovyBuckConfig, javaConfig, defaultJavaOptionsForTests, defaultJavacOptions));
    builder.addDescriptions(new GwtBinaryDescription(defaultJavaOptions));
    builder.addDescriptions(
        new HalideLibraryDescription(
            cxxBuckConfig, defaultCxxPlatform, cxxPlatforms, halideBuckConfig));
    builder.addDescriptions(
        new JavaBinaryDescription(
            defaultJavaOptions,
            defaultJavacOptions,
            javaConfig,
            defaultJavaCxxPlatform,
            cxxPlatforms));
    builder.addDescriptions(new JavaAnnotationProcessorDescription());
    builder.addDescriptions(new JavaLibraryDescription(javaConfig, defaultJavacOptions));
    builder.addDescriptions(
        new JavaTestDescription(
            javaConfig,
            defaultJavaOptionsForTests,
            defaultJavacOptions,
            defaultJavaCxxPlatform,
            cxxPlatforms));
    builder.addDescriptions(new JsBundleDescription(toolchainProvider));
    builder.addDescriptions(
        new JsBundleGenruleDescription(toolchainProvider, sandboxExecutionStrategy));
    builder.addDescriptions(new JsLibraryDescription());
    builder.addDescriptions(new KeystoreDescription());
    builder.addDescriptions(
        new KotlinLibraryDescription(kotlinBuckConfig, javaConfig, defaultJavacOptions));
    builder.addDescriptions(
        new KotlinTestDescription(
            kotlinBuckConfig, javaConfig, defaultJavaOptionsForTests, defaultJavacOptions));
    builder.addDescriptions(new NdkLibraryDescription(toolchainProvider));
    OcamlBuckConfig ocamlBuckConfig = new OcamlBuckConfig(config, defaultCxxPlatform);
    builder.addDescriptions(new OcamlBinaryDescription(ocamlBuckConfig));
    builder.addDescriptions(new OcamlLibraryDescription(ocamlBuckConfig));
    builder.addDescriptions(new PrebuiltCxxLibraryDescription(cxxBuckConfig, cxxPlatforms));
    builder.addDescriptions(PrebuiltCxxLibraryGroupDescription.of());
    builder.addDescriptions(new CxxPrecompiledHeaderDescription());
    builder.addDescriptions(new PrebuiltNativeLibraryDescription());
    builder.addDescriptions(new PrebuiltOcamlLibraryDescription());
    builder.addDescriptions(new PrebuiltPythonLibraryDescription());
    builder.addDescriptions(pythonBinaryDescription);
    PythonLibraryDescription pythonLibraryDescription =
        new PythonLibraryDescription(toolchainProvider, cxxPlatforms);
    builder.addDescriptions(pythonLibraryDescription);
    builder.addDescriptions(
        new PythonTestDescription(
            toolchainProvider,
            pythonBinaryDescription,
            pyConfig,
            cxxBuckConfig,
            defaultCxxPlatform,
            cxxPlatforms));
    builder.addDescriptions(new RemoteFileDescription(toolchainProvider));
    builder.addDescriptions(
        new RobolectricTestDescription(
            toolchainProvider,
            javaConfig,
            defaultJavaOptionsForTests,
            defaultJavacOptions,
            defaultCxxPlatform,
            defaultAndroidCompilerFactory));
    builder.addDescriptions(
        new ScalaLibraryDescription(scalaConfig, javaConfig, defaultJavacOptions));
    builder.addDescriptions(
        new ScalaTestDescription(
            scalaConfig,
            javaConfig,
            defaultJavacOptions,
            defaultJavaOptionsForTests,
            defaultCxxPlatform));
    builder.addDescriptions(new SceneKitAssetsDescription());
    builder.addDescriptions(new ShBinaryDescription());
    builder.addDescriptions(new ShTestDescription(config));
    builder.addDescriptions(new WorkerToolDescription(config));

    DescriptionCreationContext descriptionCreationContext =
        DescriptionCreationContext.builder()
            .setBuckConfig(config)
            .setToolchainProvider(toolchainProvider)
            .build();
    List<DescriptionProvider> descriptionProviders =
        pluginManager.getExtensions(DescriptionProvider.class);
    for (DescriptionProvider provider : descriptionProviders) {
      for (Description<?> description : provider.getDescriptions(descriptionCreationContext)) {
        builder.addDescriptions(description);
      }
    }

    builder.addDescriptions(VersionedAliasDescription.of());

    return builder.build();
  }
}
