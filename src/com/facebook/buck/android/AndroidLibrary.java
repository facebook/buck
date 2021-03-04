/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.AndroidLibraryDescription.CoreArg;
import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.javacd.model.UnusedDependenciesParams.UnusedDependenciesAction;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.CalculateSourceAbi;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JarBuildStepsFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDeps;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.UnusedDependenciesFinderFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class AndroidLibrary extends DefaultJavaLibrary implements AndroidPackageable {

  public static Builder builder(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ToolchainProvider toolchainProvider,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig,
      JavacFactory javacFactory,
      JavacOptions libraryJavacOptions,
      CoreArg args,
      ConfiguredCompilerFactory compilerFactory,
      CellPathResolver cellPathResolver,
      JavacOptions rDotJavacOptions) {
    return new Builder(
        buildTarget,
        projectFilesystem,
        toolchainProvider,
        params,
        graphBuilder,
        javaBuckConfig,
        javaCDBuckConfig,
        downwardApiConfig,
        javacFactory,
        libraryJavacOptions,
        args,
        compilerFactory,
        cellPathResolver,
        rDotJavacOptions);
  }

  @VisibleForTesting
  AndroidLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      JarBuildStepsFactory<?> jarBuildStepsFactory,
      SourcePathRuleFinder ruleFinder,
      Optional<SourcePath> proguardConfig,
      SortedSet<BuildRule> fullJarDeclaredDeps,
      ImmutableSortedSet<BuildRule> fullJarExportedDeps,
      ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
      ImmutableSortedSet<BuildRule> fullJarExportedProvidedDeps,
      ImmutableSortedSet<BuildRule> runtimeDeps,
      @Nullable BuildTarget abiJar,
      @Nullable BuildTarget sourceOnlyAbiJar,
      Optional<String> mavenCoords,
      Optional<SourcePath> manifestFile,
      Optional<AndroidLibraryDescription.JvmLanguage> jvmLanguage,
      ImmutableSortedSet<BuildTarget> tests,
      boolean requiredForSourceOnlyAbi,
      UnusedDependenciesAction unusedDependenciesAction,
      Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory,
      @Nullable CalculateSourceAbi sourceAbi,
      boolean isDesugarEnabled,
      boolean isInterfaceMethodsDesugarEnabled,
      boolean neverMarkAsUnusedDependency,
      boolean isJavaCDEnabled,
      Tool javaRuntimeLauncher,
      Supplier<SourcePath> javacdBinaryPathSourcePathSupplier,
      ImmutableList<String> startCommandOptions,
      int workerToolPoolSize,
      int borrowFromPoolTimeoutInSeconds) {
    super(
        buildTarget,
        projectFilesystem,
        jarBuildStepsFactory,
        ruleFinder,
        proguardConfig,
        fullJarDeclaredDeps,
        fullJarExportedDeps,
        fullJarProvidedDeps,
        fullJarExportedProvidedDeps,
        runtimeDeps,
        abiJar,
        sourceOnlyAbiJar,
        mavenCoords,
        tests,
        requiredForSourceOnlyAbi,
        unusedDependenciesAction,
        unusedDependenciesFinderFactory,
        sourceAbi,
        isDesugarEnabled,
        isInterfaceMethodsDesugarEnabled,
        neverMarkAsUnusedDependency,
        isJavaCDEnabled,
        javaRuntimeLauncher,
        javacdBinaryPathSourcePathSupplier,
        startCommandOptions,
        workerToolPoolSize,
        borrowFromPoolTimeoutInSeconds);
    this.manifestFile = manifestFile;
    this.type = jvmLanguage.map(this::evalType).orElseGet(super::getType);
  }

  /**
   * Manifest to associate with this rule. Ultimately, this will be used with the upcoming manifest
   * generation logic.
   */
  private final Optional<SourcePath> manifestFile;

  private final String type;

  public Optional<SourcePath> getManifestFile() {
    return manifestFile;
  }

  private String evalType(AndroidLibraryDescription.JvmLanguage jvmLanguage) {
    return jvmLanguage.toString().toLowerCase() + "_" + super.getType();
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public void addToCollector(
      ActionGraphBuilder graphBuilder, AndroidPackageableCollector collector) {
    super.addToCollector(graphBuilder, collector);
    manifestFile.ifPresent(
        sourcePath -> collector.addManifestPiece(this.getBuildTarget(), sourcePath));
  }

  public static class Builder {

    private final ActionGraphBuilder graphBuilder;
    private final DefaultJavaLibraryRules delegate;
    private final AndroidLibraryGraphEnhancer graphEnhancer;

    protected Builder(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        ToolchainProvider toolchainProvider,
        BuildRuleParams params,
        ActionGraphBuilder graphBuilder,
        JavaBuckConfig javaBuckConfig,
        JavaCDBuckConfig javaCDBuckConfig,
        DownwardApiConfig downwardApiConfig,
        JavacFactory javacFactory,
        JavacOptions libraryJavacOptions,
        CoreArg args,
        ConfiguredCompilerFactory compilerFactory,
        CellPathResolver cellPathResolver,
        JavacOptions rDotJavacOptions) {
      this.graphBuilder = graphBuilder;

      DefaultJavaLibraryRules.Builder delegateBuilder =
          new DefaultJavaLibraryRules.Builder(
              buildTarget,
              projectFilesystem,
              toolchainProvider,
              params,
              graphBuilder,
              compilerFactory,
              javaBuckConfig,
              javaCDBuckConfig,
              downwardApiConfig,
              args,
              cellPathResolver);
      delegateBuilder.setConstructor(
          (target,
              filesystem,
              jarBuildStepsFactory,
              ruleFinder,
              proguardConfig,
              firstOrderPackageableDeps,
              fullJarExportedDeps,
              fullJarProvidedDeps,
              fullJarExportedProvidedDeps,
              runtimeDeps,
              abiJar,
              sourceOnlyAbiJar,
              mavenCoords,
              tests,
              requiredForSourceOnlyAbi,
              unusedDependenciesAction,
              unusedDependenciesFinderFactory,
              sourceAbi,
              isDesugarEnabled,
              isInterfaceMethodsDesugarEnabled,
              neverMarkAsUnusedDependency,
              isJavaCDEnabled,
              javaRuntimeLauncher,
              javacdBinaryPathSourcePathSupplier,
              startCommandOptions,
              workerToolPoolSize,
              borrowFromPoolTimeoutInSeconds) ->
              new AndroidLibrary(
                  target,
                  filesystem,
                  jarBuildStepsFactory,
                  ruleFinder,
                  proguardConfig,
                  firstOrderPackageableDeps,
                  fullJarExportedDeps,
                  fullJarProvidedDeps,
                  fullJarExportedProvidedDeps,
                  runtimeDeps,
                  abiJar,
                  sourceOnlyAbiJar,
                  mavenCoords,
                  args.getManifest(),
                  args.getLanguage(),
                  tests,
                  requiredForSourceOnlyAbi,
                  unusedDependenciesAction,
                  unusedDependenciesFinderFactory,
                  sourceAbi,
                  isDesugarEnabled,
                  isInterfaceMethodsDesugarEnabled,
                  neverMarkAsUnusedDependency,
                  isJavaCDEnabled,
                  javaRuntimeLauncher,
                  javacdBinaryPathSourcePathSupplier,
                  startCommandOptions,
                  workerToolPoolSize,
                  borrowFromPoolTimeoutInSeconds));
      delegateBuilder.setJavacOptions(libraryJavacOptions);
      delegateBuilder.setTests(args.getTests());

      JavaLibraryDeps deps = Objects.requireNonNull(delegateBuilder.getDeps());
      BuildTarget libraryTarget =
          JavaAbis.isLibraryTarget(buildTarget)
              ? buildTarget
              : JavaAbis.getLibraryTarget(buildTarget);
      graphEnhancer =
          new AndroidLibraryGraphEnhancer(
              libraryTarget,
              projectFilesystem,
              ImmutableSortedSet.copyOf(Iterables.concat(deps.getDeps(), deps.getProvidedDeps())),
              javacFactory.create(graphBuilder, args, buildTarget.getTargetConfiguration()),
              rDotJavacOptions,
              args.getResourceUnionPackage(),
              args.getFinalRName(),
              args.isSkipNonUnionRDotJava(),
              downwardApiConfig.isEnabledForAndroid());

      getDummyRDotJava()
          .ifPresent(
              dummyRDotJava ->
                  delegateBuilder.setDeps(
                      new JavaLibraryDeps.Builder(graphBuilder)
                          .from(
                              JavaLibraryDeps.newInstance(
                                  args,
                                  graphBuilder,
                                  buildTarget.getTargetConfiguration(),
                                  compilerFactory))
                          .addDepTargets(dummyRDotJava.getBuildTarget())
                          .build()));

      delegate = delegateBuilder.build();
    }

    public AndroidLibrary build() {
      return (AndroidLibrary) delegate.buildLibrary();
    }

    /** See {@link DefaultJavaLibraryRules#buildLibraryForNullsafe()} */
    public AndroidLibrary buildLibraryForNullsafe() {
      return (AndroidLibrary) delegate.buildLibraryForNullsafe();
    }

    public BuildRule buildAbi() {
      return delegate.buildAbi();
    }

    public DummyRDotJava buildDummyRDotJava() {
      return graphEnhancer.getBuildableForAndroidResources(graphBuilder, true).get();
    }

    public Optional<DummyRDotJava> getDummyRDotJava() {
      return graphEnhancer.getBuildableForAndroidResources(graphBuilder, false);
    }
  }
}
