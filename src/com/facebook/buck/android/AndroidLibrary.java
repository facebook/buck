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

package com.facebook.buck.android;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JarBuildStepsFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.jvm.java.JavaLibraryDeps;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.UnusedDependenciesFinderFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildDeps;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.DependencyMode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;

public class AndroidLibrary extends DefaultJavaLibrary implements AndroidPackageable {

  /**
   * Manifest to associate with this rule. Ultimately, this will be used with the upcoming manifest
   * generation logic.
   */
  @AddToRuleKey private final Optional<SourcePath> manifestFile;

  public static Builder builder(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ToolchainProvider toolchainProvider,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellPathResolver,
      JavaBuckConfig javaBuckConfig,
      JavacOptions javacOptions,
      AndroidLibraryDescription.CoreArg args,
      ConfiguredCompilerFactory compilerFactory) {
    return new Builder(
        buildTarget,
        projectFilesystem,
        toolchainProvider,
        params,
        buildRuleResolver,
        cellPathResolver,
        javaBuckConfig,
        javacOptions,
        args,
        compilerFactory);
  }

  @VisibleForTesting
  AndroidLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildDeps buildDeps,
      SourcePathResolver resolver,
      JarBuildStepsFactory jarBuildStepsFactory,
      Optional<SourcePath> proguardConfig,
      SortedSet<BuildRule> fullJarDeclaredDeps,
      ImmutableSortedSet<BuildRule> fullJarExportedDeps,
      ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
      @Nullable BuildTarget abiJar,
      @Nullable BuildTarget sourceOnlyAbiJar,
      Optional<String> mavenCoords,
      Optional<SourcePath> manifestFile,
      ImmutableSortedSet<BuildTarget> tests,
      boolean requiredForSourceOnlyAbi,
      UnusedDependenciesAction unusedDependenciesAction,
      Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory) {
    super(
        buildTarget,
        projectFilesystem,
        buildDeps,
        resolver,
        jarBuildStepsFactory,
        proguardConfig,
        fullJarDeclaredDeps,
        fullJarExportedDeps,
        fullJarProvidedDeps,
        abiJar,
        sourceOnlyAbiJar,
        mavenCoords,
        tests,
        requiredForSourceOnlyAbi,
        unusedDependenciesAction,
        unusedDependenciesFinderFactory);
    this.manifestFile = manifestFile;
  }

  public Optional<SourcePath> getManifestFile() {
    return manifestFile;
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    super.addToCollector(collector);
    manifestFile.ifPresent(collector::addManifestPiece);
  }

  public static class Builder {
    private final BuildRuleResolver buildRuleResolver;
    private final DefaultJavaLibraryRules delegate;
    private final AndroidLibraryGraphEnhancer graphEnhancer;

    protected Builder(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        ToolchainProvider toolchainProvider,
        BuildRuleParams params,
        BuildRuleResolver buildRuleResolver,
        CellPathResolver cellPathResolver,
        JavaBuckConfig javaBuckConfig,
        JavacOptions javacOptions,
        AndroidLibraryDescription.CoreArg args,
        ConfiguredCompilerFactory compilerFactory) {
      this.buildRuleResolver = buildRuleResolver;
      DefaultJavaLibraryRules.Builder delegateBuilder =
          new DefaultJavaLibraryRules.Builder(
              buildTarget,
              projectFilesystem,
              toolchainProvider,
              params,
              buildRuleResolver,
              cellPathResolver,
              compilerFactory,
              javaBuckConfig,
              args);
      delegateBuilder.setConstructor(
          new DefaultJavaLibraryRules.DefaultJavaLibraryConstructor() {
            @Override
            public DefaultJavaLibrary newInstance(
                BuildTarget buildTarget,
                ProjectFilesystem projectFilesystem,
                BuildDeps buildDeps,
                SourcePathResolver resolver,
                JarBuildStepsFactory jarBuildStepsFactory,
                Optional<SourcePath> proguardConfig,
                SortedSet<BuildRule> firstOrderPackageableDeps,
                ImmutableSortedSet<BuildRule> fullJarExportedDeps,
                ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
                @Nullable BuildTarget abiJar,
                @Nullable BuildTarget sourceOnlyAbiJar,
                Optional<String> mavenCoords,
                ImmutableSortedSet<BuildTarget> tests,
                boolean requiredForSourceOnlyAbi,
                UnusedDependenciesAction unusedDependenciesAction,
                Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory) {
              return new AndroidLibrary(
                  buildTarget,
                  projectFilesystem,
                  buildDeps,
                  resolver,
                  jarBuildStepsFactory,
                  proguardConfig,
                  firstOrderPackageableDeps,
                  fullJarExportedDeps,
                  fullJarProvidedDeps,
                  abiJar,
                  sourceOnlyAbiJar,
                  mavenCoords,
                  args.getManifest(),
                  tests,
                  requiredForSourceOnlyAbi,
                  unusedDependenciesAction,
                  unusedDependenciesFinderFactory);
            }
          });
      delegateBuilder.setJavacOptions(javacOptions);
      delegateBuilder.setTests(args.getTests());

      JavaLibraryDeps deps = Preconditions.checkNotNull(delegateBuilder.getDeps());
      BuildTarget libraryTarget =
          HasJavaAbi.isLibraryTarget(buildTarget)
              ? buildTarget
              : HasJavaAbi.getLibraryTarget(buildTarget);
      graphEnhancer =
          new AndroidLibraryGraphEnhancer(
              libraryTarget,
              projectFilesystem,
              ImmutableSortedSet.copyOf(Iterables.concat(deps.getDeps(), deps.getProvidedDeps())),
              JavacFactory.create(
                  new SourcePathRuleFinder(buildRuleResolver), javaBuckConfig, args),
              javacOptions,
              DependencyMode.FIRST_ORDER,
              /* forceFinalResourceIds */ false,
              args.getResourceUnionPackage(),
              args.getFinalRName(),
              /* useOldStyleableFormat */ false,
              args.isSkipNonUnionRDotJava());

      getDummyRDotJava()
          .ifPresent(
              dummyRDotJava -> {
                delegateBuilder.setDeps(
                    new JavaLibraryDeps.Builder(buildRuleResolver)
                        .from(JavaLibraryDeps.newInstance(args, buildRuleResolver))
                        .addDepTargets(dummyRDotJava.getBuildTarget())
                        .build());
              });

      delegate = delegateBuilder.build();
    }

    public AndroidLibrary build() {
      return (AndroidLibrary) delegate.buildLibrary();
    }

    public BuildRule buildAbi() {
      return delegate.buildAbi();
    }

    public DummyRDotJava buildDummyRDotJava() {
      return graphEnhancer.getBuildableForAndroidResources(buildRuleResolver, true).get();
    }

    public Optional<DummyRDotJava> getDummyRDotJava() {
      return graphEnhancer.getBuildableForAndroidResources(buildRuleResolver, false);
    }
  }
}
