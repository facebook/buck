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

import com.facebook.buck.android.AndroidLibraryDescription.JvmLanguage;
import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.ConfiguredCompiler;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryBuilder;
import com.facebook.buck.jvm.java.JarBuildStepsFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.DependencyMode;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
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
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellRoots,
      JavaBuckConfig javaBuckConfig,
      JavacOptions javacOptions,
      AndroidLibraryDescription.CoreArg args,
      AndroidLibraryCompilerFactory compilerFactory) {
    return new Builder(
        targetGraph,
        buildTarget,
        projectFilesystem,
        params,
        buildRuleResolver,
        cellRoots,
        javaBuckConfig,
        javacOptions,
        args,
        compilerFactory);
  }

  @VisibleForTesting
  AndroidLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathResolver resolver,
      JarBuildStepsFactory jarBuildStepsFactory,
      Optional<SourcePath> proguardConfig,
      SortedSet<BuildRule> fullJarDeclaredDeps,
      ImmutableSortedSet<BuildRule> fullJarExportedDeps,
      ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
      @Nullable BuildTarget abiJar,
      Optional<String> mavenCoords,
      Optional<SourcePath> manifestFile,
      ImmutableSortedSet<BuildTarget> tests,
      boolean requiredForSourceAbi) {
    super(
        buildTarget,
        projectFilesystem,
        params,
        resolver,
        jarBuildStepsFactory,
        proguardConfig,
        fullJarDeclaredDeps,
        fullJarExportedDeps,
        fullJarProvidedDeps,
        abiJar,
        mavenCoords,
        tests,
        requiredForSourceAbi);
    this.manifestFile = manifestFile;
  }

  public Optional<SourcePath> getManifestFile() {
    return manifestFile;
  }

  public static class Builder extends DefaultJavaLibraryBuilder {
    private final AndroidLibraryDescription.CoreArg args;
    private final AndroidLibraryCompilerFactory compilerFactory;

    private AndroidLibraryDescription.JvmLanguage language =
        AndroidLibraryDescription.JvmLanguage.JAVA;
    private Optional<SourcePath> androidManifest = Optional.empty();

    protected Builder(
        TargetGraph targetGraph,
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        BuildRuleResolver buildRuleResolver,
        CellPathResolver cellRoots,
        JavaBuckConfig javaBuckConfig,
        JavacOptions javacOptions,
        AndroidLibraryDescription.CoreArg args,
        AndroidLibraryCompilerFactory compilerFactory) {
      super(
          targetGraph,
          buildTarget,
          projectFilesystem,
          params,
          buildRuleResolver,
          cellRoots,
          javaBuckConfig);
      this.args = args;
      this.compilerFactory = compilerFactory;
      setJavacOptions(javacOptions);
      setArgs(args);
      // Set only if this is not Scala/Kotlin
      setCompileAgainstAbis(
          javaBuckConfig.shouldCompileAgainstAbis()
              && !args.getLanguage().filter(l -> l != JvmLanguage.JAVA).isPresent());
    }

    @Override
    public DefaultJavaLibraryBuilder setArgs(JavaLibraryDescription.CoreArg args) {
      super.setArgs(args);
      AndroidLibraryDescription.CoreArg androidArgs = (AndroidLibraryDescription.CoreArg) args;
      language = androidArgs.getLanguage().orElse(AndroidLibraryDescription.JvmLanguage.JAVA);

      if (androidArgs.getProvidedDepsQuery().isPresent()) {
        Query providedDepsQuery = androidArgs.getProvidedDepsQuery().get();
        Preconditions.checkNotNull(providedDepsQuery.getResolvedQuery());
        setProvidedDeps(
            RichStream.from(args.getProvidedDeps())
                .concat(providedDepsQuery.getResolvedQuery().stream())
                .toImmutableSortedSet(Ordering.natural()));
      }
      return setManifestFile(androidArgs.getManifest());
    }

    @Override
    public DefaultJavaLibraryBuilder setManifestFile(Optional<SourcePath> manifestFile) {
      androidManifest = manifestFile;
      return this;
    }

    public DummyRDotJava buildDummyRDotJava() {
      return newHelper().buildDummyRDotJava();
    }

    public Optional<DummyRDotJava> getDummyRDotJava() {
      return newHelper().getDummyRDotJava();
    }

    @Override
    protected BuilderHelper newHelper() {
      return new BuilderHelper();
    }

    protected class BuilderHelper extends DefaultJavaLibraryBuilder.BuilderHelper {
      @Nullable private ConfiguredCompilerFactory androidCompiler;
      @Nullable private AndroidLibraryGraphEnhancer graphEnhancer;

      @Override
      protected DefaultJavaLibrary build() {
        AndroidLibrary result =
            new AndroidLibrary(
                libraryTarget,
                projectFilesystem,
                getFinalParams(),
                sourcePathResolver,
                getJarBuildStepsFactory(),
                proguardConfig,
                getFinalFullJarDeclaredDeps(),
                fullJarExportedDeps,
                fullJarProvidedDeps,
                getAbiJar(),
                mavenCoords,
                androidManifest,
                tests,
                getRequiredForSourceAbi());

        return result;
      }

      @Override
      protected JarBuildStepsFactory buildJarBuildStepsFactory() {
        return new JarBuildStepsFactory(
            projectFilesystem,
            ruleFinder,
            getConfiguredCompiler(),
            srcs,
            resources,
            resourcesRoot,
            Optional.empty(), // ManifestFile for androidLibrary is something else
            postprocessClassesCommands,
            getAbiClasspath(),
            getAndroidCompiler().trackClassUsage(Preconditions.checkNotNull(getJavacOptions())),
            getFinalCompileTimeClasspathSourcePaths(),
            classesToRemoveFromJar,
            getRequiredForSourceAbi());
      }

      protected DummyRDotJava buildDummyRDotJava() {
        return getGraphEnhancer().getBuildableForAndroidResources(buildRuleResolver, true).get();
      }

      protected Optional<DummyRDotJava> getDummyRDotJava() {
        return getGraphEnhancer().getBuildableForAndroidResources(buildRuleResolver, false);
      }

      protected AndroidLibraryGraphEnhancer getGraphEnhancer() {
        if (graphEnhancer == null) {
          final Supplier<ImmutableList<BuildRule>> queriedDepsSupplier = buildQueriedDepsSupplier();
          final Supplier<ImmutableList<BuildRule>> exportedDepsSupplier =
              buildExportedDepsSupplier();
          graphEnhancer =
              new AndroidLibraryGraphEnhancer(
                  libraryTarget,
                  projectFilesystem,
                  initialParams.withExtraDeps(
                      () ->
                          ImmutableSortedSet.copyOf(
                              Iterables.concat(
                                  queriedDepsSupplier.get(), exportedDepsSupplier.get()))),
                  getJavac(),
                  getJavacOptions(),
                  DependencyMode.FIRST_ORDER,
                  /* forceFinalResourceIds */ false,
                  args.getResourceUnionPackage(),
                  args.getFinalRName(),
                  false);
        }
        return graphEnhancer;
      }

      private Supplier<ImmutableList<BuildRule>> buildQueriedDepsSupplier() {
        return args.getDepsQuery().isPresent()
            ? Suppliers.memoize(
                () ->
                    Preconditions.checkNotNull(args.getDepsQuery().get().getResolvedQuery())
                        .stream()
                        .map(buildRuleResolver::getRule)
                        .collect(MoreCollectors.toImmutableList()))
            : ImmutableList::of;
      }

      private Supplier<ImmutableList<BuildRule>> buildExportedDepsSupplier() {
        return Suppliers.memoize(
            () ->
                buildRuleResolver
                    .getAllRulesStream(args.getExportedDeps())
                    .collect(MoreCollectors.toImmutableList()));
      }

      @Override
      protected ImmutableSortedSet<BuildRule> buildFinalFullJarDeclaredDeps() {
        return RichStream.from(super.buildFinalFullJarDeclaredDeps())
            .concat(RichStream.from(getDummyRDotJava()))
            .concat(RichStream.fromSupplierOfIterable(buildQueriedDepsSupplier()))
            .toImmutableSortedSet(Ordering.natural());
      }

      @Override
      protected ConfiguredCompiler buildConfiguredCompiler() {
        return getAndroidCompiler()
            .configure(args, Preconditions.checkNotNull(getJavacOptions()), buildRuleResolver);
      }

      protected ConfiguredCompilerFactory getAndroidCompiler() {
        if (androidCompiler == null) {
          androidCompiler = compilerFactory.getCompiler(language);
        }

        return androidCompiler;
      }
    }
  }
}
