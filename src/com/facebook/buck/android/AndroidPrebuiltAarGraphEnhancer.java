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

import com.facebook.buck.io.ProjectFilesystem.CopySourceMode;
import com.facebook.buck.java.JarDirectoryStepHelper;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.zip.UnzipStep;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

class AndroidPrebuiltAarGraphEnhancer {

  private static final Flavor AAR_UNZIP_FLAVOR = ImmutableFlavor.of("aar_unzip");

  /** Utility class: do not instantiate. */
  private AndroidPrebuiltAarGraphEnhancer() {}

  /**
   * Creates a build rule to unzip the prebuilt AAR and get the components needed for the
   * AndroidPrebuiltAar, PrebuiltJar, and AndroidResource
   */
  static UnzipAar enhance(
      BuildRuleParams originalBuildRuleParams,
      SourcePath aarFile,
      BuildRuleResolver ruleResolver) {
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    UnflavoredBuildTarget originalBuildTarget =
        originalBuildRuleParams.getBuildTarget().checkUnflavored();

    BuildRuleParams unzipAarParams = originalBuildRuleParams.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_UNZIP_FLAVOR),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.copyOf(
                resolver.filterBuildRuleInputs(aarFile))));
    UnzipAar unzipAar = new UnzipAar(unzipAarParams, resolver, aarFile);
    return ruleResolver.addToIndex(unzipAar);
  }

  static class UnzipAar extends AbstractBuildRule {

    @AddToRuleKey
    private final SourcePath aarFile;
    private final Path unpackDirectory;
    private final Path uberClassesJar;

    private UnzipAar(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver,
        SourcePath aarFile) {
      super(buildRuleParams, resolver);
      this.aarFile = aarFile;
      this.unpackDirectory = BuildTargets.getScratchPath(
          buildRuleParams.getBuildTarget(),
          "__unpack_%s__");
      this.uberClassesJar = BuildTargets.getScratchPath(
          buildRuleParams.getBuildTarget(),
          "__uber_classes_%s__/classes.jar");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        BuildableContext buildableContext) {
      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), unpackDirectory));
      steps.add(
          new UnzipStep(
              getProjectFilesystem(),
              getResolver().getPath(aarFile),
              unpackDirectory));
      steps.add(new TouchStep(getProjectFilesystem(), getProguardConfig()));
      steps.add(new MkdirStep(getProjectFilesystem(), getAssetsDirectory()));
      steps.add(new MkdirStep(getProjectFilesystem(), getNativeLibsDirectory()));

      // We take the classes.jar file that is required to exist in an .aar and merge it with any
      // .jar files under libs/ into an "uber" jar. We do this for simplicity because we do not know
      // how many entries there are in libs/ at graph enhancement time, but we need to make sure
      // that all of the .class files in the .aar get packaged. As it is implemented today, an
      // android_library that depends on an android_prebuilt_aar can compile against anything in the
      // .aar's classes.jar or libs/.
      steps.add(new MkdirStep(getProjectFilesystem(), uberClassesJar.getParent()));
      steps.add(new AbstractExecutionStep("create_uber_classes_jar") {
        @Override
        public int execute(ExecutionContext context) {
          Path libsDirectory = unpackDirectory.resolve("libs");
          boolean dirDoesNotExistOrIsEmpty;
          if (!getProjectFilesystem().exists(libsDirectory)) {
            dirDoesNotExistOrIsEmpty = true;
          } else {
            try {
              dirDoesNotExistOrIsEmpty =
                  getProjectFilesystem().getDirectoryContents(libsDirectory).isEmpty();
            } catch (IOException e) {
              context.logError(e, "Failed to get directory contents of %s", libsDirectory);
              return 1;
            }
          }

          Path classesJar = unpackDirectory.resolve("classes.jar");
          if (dirDoesNotExistOrIsEmpty) {
            try {
              getProjectFilesystem().copy(classesJar, uberClassesJar, CopySourceMode.FILE);
            } catch (IOException e) {
              context.logError(e, "Failed to copy from %s to %s", classesJar, uberClassesJar);
              return 1;
            }
          } else {
            // Glob all of the contents from classes.jar and the entries in libs/ into a single JAR.
            ImmutableSet.Builder<Path> entriesToJarBuilder = ImmutableSet.builder();
            entriesToJarBuilder.add(classesJar);
            try {
              entriesToJarBuilder.addAll(
                  getProjectFilesystem().getDirectoryContents(libsDirectory));
            } catch (IOException e) {
              context.logError(e, "Failed to get directory contents of %s", libsDirectory);
              return 1;
            }

            ImmutableSet<Path> entriesToJar = entriesToJarBuilder.build();
            try {
              JarDirectoryStepHelper.createJarFile(
                  getProjectFilesystem(),
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

      buildableContext.recordArtifact(unpackDirectory);
      buildableContext.recordArtifact(uberClassesJar);
      return steps.build();
    }

    @Override
    @Nullable
    public Path getPathToOutput() {
      return null;
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

    Path getNativeLibsDirectory() {
      return unpackDirectory.resolve("jni");
    }
  }
}
