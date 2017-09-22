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
import com.facebook.buck.io.ProjectFilesystem;
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
import com.facebook.buck.util.DependencyMode;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
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
      ConfiguredCompilerFactory compilerFactory) {
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
        ConfiguredCompilerFactory compilerFactory) {
      super(
          targetGraph,
          buildTarget,
          projectFilesystem,
          params,
          buildRuleResolver,
          cellRoots,
          compilerFactory,
          javaBuckConfig);
      this.args = args;
      setJavacOptions(javacOptions);
      setArgs(args);
    }

    @Override
    public DefaultJavaLibraryBuilder setArgs(JavaLibraryDescription.CoreArg args) {
      super.setArgs(args);
      AndroidLibraryDescription.CoreArg androidArgs = (AndroidLibraryDescription.CoreArg) args;

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
                fullJarExportedDepsSupplier.get(),
                fullJarProvidedDeps,
                getAbiJar(),
                mavenCoords,
                androidManifest,
                tests,
                getRequiredForSourceAbi());

        return result;
      }

      protected DummyRDotJava buildDummyRDotJava() {
        return getGraphEnhancer().getBuildableForAndroidResources(buildRuleResolver, true).get();
      }

      protected Optional<DummyRDotJava> getDummyRDotJava() {
        return getGraphEnhancer().getBuildableForAndroidResources(buildRuleResolver, false);
      }

      protected AndroidLibraryGraphEnhancer getGraphEnhancer() {
        if (graphEnhancer == null) {
          graphEnhancer =
              new AndroidLibraryGraphEnhancer(
                  libraryTarget,
                  projectFilesystem,
                  ImmutableSortedSet.copyOf(
                      Iterables.concat(
                          initialParams.getBuildDeps(),
                          queriedDepsSupplier.get(),
                          fullJarExportedDepsSupplier.get())),
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

      @Override
      protected ImmutableSortedSet<BuildRule> buildFinalFullJarDeclaredDeps() {
        return RichStream.from(super.buildFinalFullJarDeclaredDeps())
            .concat(RichStream.from(getDummyRDotJava()))
            .toImmutableSortedSet(Ordering.natural());
      }
    }
  }
}
