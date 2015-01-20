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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.ProjectFilesystem.CopySourceMode;
import com.facebook.buck.java.JarDirectoryStepHelper;
import com.facebook.buck.java.Javac;
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
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.OutputOnlyBuildRule;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.zip.UnzipStep;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.regex.Pattern;

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
  static AndroidPrebuiltAar enhance(
      BuildRuleParams originalBuildRuleParams,
      SourcePath aarFile,
      BuildRuleResolver ruleResolver,
      Javac javac,
      JavacOptions javacOptions) {
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    // unzip_aar
    BuildTarget originalBuildTarget = originalBuildRuleParams.getBuildTarget();
    BuildRuleParams unzipAarParams = originalBuildRuleParams.copyWithChanges(
        UNZIP_AAR_TYPE,
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_UNZIP_FLAVOR),
        originalBuildRuleParams.getDeclaredDeps(),
        originalBuildRuleParams.getExtraDeps());
    UnzipAar unzipAar = new UnzipAar(unzipAarParams, pathResolver, aarFile);
    ruleResolver.addToIndex(unzipAar);

    // unzip_aar#aar_classes_jar
    BuildRuleParams classesJarParams = originalBuildRuleParams.copyWithChanges(
        OutputOnlyBuildRule.TYPE,
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_CLASSES_JAR_FLAVOR),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(unzipAar),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    OutputOnlyBuildRule classesJar = new OutputOnlyBuildRule(
        classesJarParams,
        pathResolver,
        unzipAar.getPathToClassesJar());
    ruleResolver.addToIndex(classesJar);

    // prebuilt_jar
    BuildRuleParams prebuiltJarParams = originalBuildRuleParams.copyWithChanges(
        PrebuiltJarDescription.TYPE,
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_PREBUILT_JAR_FLAVOR),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(unzipAar),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    PrebuiltJar prebuiltJar = new PrebuiltJar(
        /* params */ prebuiltJarParams,
        pathResolver,
        new BuildTargetSourcePath(classesJar.getBuildTarget()),
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
        manifestParams,
        pathResolver,
        unzipAar.getAndroidManifest());
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
        pathResolver,
        /* deps */ ImmutableSortedSet.<BuildRule>of(unzipAar),
        unzipAar.getResDirectory(),
        resSrcs,
        /* rDotJavaPackage */ null,
        /* assets */ unzipAar.getAssetsDirectory(),
        assetsSrcs,
        new BuildTargetSourcePath(manifest.getBuildTarget()),
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
    return new AndroidPrebuiltAar(
        androidLibraryParams,
        pathResolver,
        unzipAar.getProguardConfig(),
        prebuiltJar,
        androidResource,
        javac,
        javacOptions);
  }

  private static class UnzipAar extends AbstractBuildRule {

    private final SourcePath aarFile;
    private final Path unpackDirectory;
    private final Path uberClassesJar;

    private UnzipAar(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver,
        SourcePath aarFile) {
      super(buildRuleParams, resolver);
      this.aarFile = aarFile;
      this.unpackDirectory = BuildTargets.getBinPath(
          buildRuleParams.getBuildTarget(),
          "__unpack_%s__");
      this.uberClassesJar = BuildTargets.getBinPath(
          buildRuleParams.getBuildTarget(),
          "__uber_classes_%s__/classes.jar");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(BuildContext context,
        BuildableContext buildableContext) {
      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      steps.add(new MakeCleanDirectoryStep(unpackDirectory));
      steps.add(new UnzipStep(getResolver().getPath(aarFile), unpackDirectory));
      steps.add(new TouchStep(getProguardConfig()));
      steps.add(new MkdirStep(getAssetsDirectory()));

      // We take the classes.jar file that is required to exist in an .aar and merge it with any
      // .jar files under libs/ into an "uber" jar. We do this for simplicity because we do not know
      // how many entries there are in libs/ at graph enhancement time, but we need to make sure
      // that all of the .class files in the .aar get packaged. As it is implemented today, an
      // android_library that depends on an android_prebuilt_aar can compile against anything in the
      // .aar's classes.jar or libs/.
      steps.add(new MkdirStep(uberClassesJar.getParent()));
      steps.add(new AbstractExecutionStep("create_uber_classes_jar") {
        @Override
        public int execute(ExecutionContext context) {
          ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
          Path libsDirectory = unpackDirectory.resolve("libs");
          boolean dirDoesNotExistOrIsEmpty;
          if (!projectFilesystem.exists(libsDirectory)) {
            dirDoesNotExistOrIsEmpty = true;
          } else {
            try {
              dirDoesNotExistOrIsEmpty =
                  projectFilesystem.getDirectoryContents(libsDirectory).isEmpty();
            } catch (IOException e) {
              context.logError(e, "Failed to get directory contents of %s", libsDirectory);
              return 1;
            }
          }

          Path classesJar = unpackDirectory.resolve("classes.jar");
          if (dirDoesNotExistOrIsEmpty) {
            try {
              projectFilesystem.copy(classesJar, uberClassesJar, CopySourceMode.FILE);
            } catch (IOException e) {
              context.logError(e, "Failed to copy from %s to %s", classesJar, uberClassesJar);
              return 1;
            }
          } else {
            // Glob all of the contents from classes.jar and the entries in libs/ into a single JAR.
            ImmutableSet.Builder<Path> entriesToJarBuilder = ImmutableSet.builder();
            entriesToJarBuilder.add(classesJar);
            try {
              entriesToJarBuilder.addAll(projectFilesystem.getDirectoryContents(libsDirectory));
            } catch (IOException e) {
              context.logError(e, "Failed to get directory contents of %s", libsDirectory);
              return 1;
            }

            ImmutableSet<Path> entriesToJar = entriesToJarBuilder.build();
            try {
              JarDirectoryStepHelper.createJarFile(
                  uberClassesJar,
                  entriesToJar,
                  /* mainClass */ null,
                  /* manifestFile */ null,
                  /* mergeManifests */ true,
                  /* blacklist */ ImmutableList.<Pattern>of(),
                  context);
            } catch (IOException e) {
              context.logError(e, "Failed to jar %s into %s", entriesToJar, uberClassesJar);
              return 1;
            }
          }
          return 0;
        }
      });

      buildableContext.recordArtifactsInDirectory(unpackDirectory);
      buildableContext.recordArtifact(uberClassesJar);
      return steps.build();
    }

    @Override
    @Nullable
    public Path getPathToOutputFile() {
      return null;
    }

    @Override
    protected ImmutableCollection<Path> getInputsToCompareToOutput() {
      return getResolver().filterInputsToCompareToOutput(Collections.singleton(aarFile));
    }

    @Override
    protected Builder appendDetailsToRuleKey(Builder builder) {
      return builder;
    }

    Path getPathToClassesJar() {
      return uberClassesJar;
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
