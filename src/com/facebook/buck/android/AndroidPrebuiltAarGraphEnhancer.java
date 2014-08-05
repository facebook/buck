/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.PrebuiltJar;
import com.facebook.buck.java.PrebuiltJarDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.OutputOnlyBuildRule;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.zip.UnzipStep;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collections;

import javax.annotation.Nullable;

class AndroidPrebuiltAarGraphEnhancer {

  private static final BuildRuleType UNZIP_AAR_TYPE = new BuildRuleType("unzip_aar");

  private static final Flavor AAR_UNZIP_FLAVOR = new Flavor("aar_unzip");
  private static final Flavor AAR_CLASSES_JAR_FLAVOR = new Flavor("aar_classes_jar");
  private static final Flavor AAR_MANIFEST = new Flavor("aar_manifest");
  private static final Flavor AAR_PREBUILT_JAR_FLAVOR = new Flavor("aar_prebuilt_jar");
  private static final Flavor AAR_ANDROID_RESOURCE_FLAVOR = new Flavor("aar_android_resource");

  /** Utility class: do not instantiate. */
  private AndroidPrebuiltAarGraphEnhancer() {}

  /**
   * Creates a rooted DAG of build rules:
   * <ul>
   *   <li>{@code unzip_aar} depends on the deps specified to the original {@code android_aar}
   *   <li>{@code prebuilt_jar} depends on {@code unzip_aar}
   *   <li>{@code android_resource} depends on {@code unzip_aar}
   *   <li>{@code android_library} depends on {@code android_resource}, {@code prebuilt_jar}, and
   *       {@code unzip_aar}
   * </ul>
   * Therefore, the return value is an {link AndroidLibrary} with no {@code srcs}.
   */
  static BuildRule enhance(
      BuildRuleParams originalBuildRuleParams,
      SourcePath aarFile,
      BuildRuleResolver ruleResolver) {
    // unzip_aar
    BuildTarget originalBuildTarget = originalBuildRuleParams.getBuildTarget();
    BuildRuleParams unzipAarParams = originalBuildRuleParams.copyWithChanges(
        UNZIP_AAR_TYPE,
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_UNZIP_FLAVOR),
        originalBuildRuleParams.getDeclaredDeps(),
        originalBuildRuleParams.getExtraDeps());
    UnzipAar unzipAar = new UnzipAar(unzipAarParams, aarFile);
    ruleResolver.addToIndex(unzipAar);

    // unzip_aar#aar_classes_jar
    BuildRuleParams classesJarParams = originalBuildRuleParams.copyWithChanges(
        OutputOnlyBuildRule.TYPE,
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_CLASSES_JAR_FLAVOR),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(unzipAar),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    OutputOnlyBuildRule classesJar = new OutputOnlyBuildRule(
        classesJarParams, unzipAar.getPathToClassesJar());
    ruleResolver.addToIndex(classesJar);

    // prebuilt_jar
    BuildRuleParams prebuiltJarParams = originalBuildRuleParams.copyWithChanges(
        PrebuiltJarDescription.TYPE,
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_PREBUILT_JAR_FLAVOR),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(unzipAar),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    PrebuiltJar prebuiltJar = new PrebuiltJar(
        /* params */ prebuiltJarParams,
        new BuildRuleSourcePath(classesJar),
        /* sourceJar */ Optional.<SourcePath>absent(),
        /* gwtJar */ Optional.<SourcePath>absent(),
        /* javadocUrl */ Optional.<String>absent());
    ruleResolver.addToIndex(prebuiltJar);

    // unzip_aar#aar_manifest
    BuildRuleParams manifestParams = originalBuildRuleParams.copyWithChanges(
        OutputOnlyBuildRule.TYPE,
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_MANIFEST),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(unzipAar),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    OutputOnlyBuildRule manifest = new OutputOnlyBuildRule(
        manifestParams, unzipAar.getAndroidManifest());
    ruleResolver.addToIndex(manifest);

    // android_resource
    BuildRuleParams androidResourceParams = originalBuildRuleParams.copyWithChanges(
        AndroidResourceDescription.TYPE,
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_ANDROID_RESOURCE_FLAVOR),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(manifest),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());

    // Because all resources and assets are generated files, we specify them as empty collections.
    ImmutableSortedSet<Path> resSrcs = ImmutableSortedSet.of();
    ImmutableSortedSet<Path> assetsSrcs = ImmutableSortedSet.of();

    AndroidResource androidResource = new AndroidResource(
        androidResourceParams,
        /* deps */ ImmutableSortedSet.<BuildRule>of(unzipAar),
        unzipAar.getResDirectory(),
        resSrcs,
        /* rDotJavaPackage */ null,
        /* assets */ unzipAar.getAssetsDirectory(),
        assetsSrcs,
        new BuildRuleSourcePath(manifest),
        /* hasWhitelistedStrings */ false);
    ruleResolver.addToIndex(androidResource);

    // android_library
    BuildRuleParams androidLibraryParams = originalBuildRuleParams.copyWithChanges(
        AndroidLibraryDescription.TYPE,
        originalBuildTarget,
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(
            androidResource,
            prebuiltJar,
            unzipAar),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    AndroidLibrary library = new AndroidLibrary(
        androidLibraryParams,
        /* srcs */ ImmutableSortedSet.<SourcePath>of(),
        /* resources */ ImmutableSortedSet.<SourcePath>of(),
        Optional.of(unzipAar.getProguardConfig()),
        /* postprocessClassesCommands */ ImmutableList.<String>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(prebuiltJar),
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        /* javacOptions */ JavacOptions.DEFAULTS,
        /* resourcesRoot */ Optional.<Path>absent(),
        /* manifestFile */ Optional.<SourcePath>absent(),
        /* isPrebuiltAar */ true);

    return library;
  }

  private static class UnzipAar extends AbstractBuildRule {

    private final SourcePath aarFile;
    private final Path unpackDirectory;

    private UnzipAar(BuildRuleParams buildRuleParams, SourcePath aarFile) {
      super(buildRuleParams);
      this.aarFile = Preconditions.checkNotNull(aarFile);
      this.unpackDirectory = BuildTargets.getBinPath(
          buildRuleParams.getBuildTarget(),
          "__unpack_%s__");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(BuildContext context,
        BuildableContext buildableContext) {
      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      steps.add(new MakeCleanDirectoryStep(unpackDirectory));
      steps.add(new UnzipStep(aarFile.resolve(), unpackDirectory));
      steps.add(new TouchStep(getProguardConfig()));
      steps.add(new MkdirStep(getAssetsDirectory()));

      // For now, we do not support an .aar file that has entries in the libs directory.
      // Basically, this is for simplicity because we do not know how many more prebuilt_jar rules
      // we would have to add at enhacement time. If this is a problem in practice, then we can
      // add a step that generates an uber-jar from classes.jar and the contents of the libs
      // directory and then have the prebuilt_jar step wrap the uber-jar instead of classes.jar.
      // Because there are not many .aar files in the wild, it is difficult to tell whether this
      // will be a pain point for developers.
      steps.add(new AbstractExecutionStep("check_for_libs") {
        @Override
        public int execute(ExecutionContext context) {
          Path libsDirectory = unpackDirectory.resolve("libs");
          ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
          if (!projectFilesystem.exists(libsDirectory)) {
            return 0;
          }

          if (projectFilesystem.listFiles(libsDirectory).length > 0) {
            context.logError(
                new IllegalArgumentException(),
                "Error processing %s for %s. " +
                    "Currently, Buck does not support .aar files with lib entries. " +
                    "If this is a problem for you, please file an issue on GitHub.",
                aarFile,
                getBuildTarget());
            return 1;
          } else {
            return 0;
          }
        }
      });

      buildableContext.recordArtifactsInDirectory(unpackDirectory);
      return steps.build();
    }

    @Override
    @Nullable
    public Path getPathToOutputFile() {
      return null;
    }

    @Override
    protected Iterable<Path> getInputsToCompareToOutput() {
      return SourcePaths.filterInputsToCompareToOutput(Collections.singleton(aarFile));
    }

    @Override
    protected Builder appendDetailsToRuleKey(Builder builder) {
      return builder;
    }

    Path getPathToClassesJar() {
      return unpackDirectory.resolve("classes.jar");
    }

    Path getResDirectory() {
      return unpackDirectory.resolve("res");
    }

    Path getAssetsDirectory() {
      return unpackDirectory.resolve("assets");
    }

    Path getAndroidManifest() {
      return unpackDirectory.resolve("AndroidManifest.xml");
    }

    Path getProguardConfig() {
      return unpackDirectory.resolve("proguard.txt");
    }
  }
}
