/*
 * Copyright 2015-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JarDirectoryStepHelper;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.rules.Sha1HashCode;
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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class UnzipAar extends AbstractBuildRule
    implements InitializableFromDisk<UnzipAar.BuildOutput> {

  private static final String METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE = "R_DOT_JAVA_PACKAGE";
  private static final String METADATA_KEY_FOR_R_DOT_TXT_SHA1 = "R_DOT_TXT_SHA1";

  @AddToRuleKey
  private final SourcePath aarFile;
  private final Path unpackDirectory;
  private final Path uberClassesJar;
  private final BuildOutputInitializer<BuildOutput> outputInitializer;

  UnzipAar(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      SourcePath aarFile) {
    super(buildRuleParams, resolver);
    this.aarFile = aarFile;
    BuildTarget buildTarget = buildRuleParams.getBuildTarget();
    this.unpackDirectory = BuildTargets.getScratchPath(buildTarget, "__unpack_%s__");
    this.uberClassesJar = BuildTargets.getScratchPath(
        buildTarget,
        "__uber_classes_%s__/classes.jar");
    this.outputInitializer = new BuildOutputInitializer<>(buildTarget, this);
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
    steps.add(new MkdirStep(getProjectFilesystem(), getResolver().getPath(getAssetsDirectory())));
    steps.add(new MkdirStep(getProjectFilesystem(), getNativeLibsDirectory()));
    steps.add(new TouchStep(getProjectFilesystem(), getTextSymbolsFile()));

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
            getProjectFilesystem().copy(
                classesJar,
                uberClassesJar,
                ProjectFilesystem.CopySourceMode.FILE);
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

    steps.add(new ExtractFromAndroidManifestStep(
        getAndroidManifest(),
        getProjectFilesystem(),
        buildableContext,
        METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE));
    steps.add(new RecordFileSha1Step(
        getProjectFilesystem(),
        getTextSymbolsFile(),
        METADATA_KEY_FOR_R_DOT_TXT_SHA1,
        buildableContext));

    buildableContext.recordArtifact(unpackDirectory);
    buildableContext.recordArtifact(uberClassesJar);
    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    Optional<String> rDotPackage = onDiskBuildInfo.getValue(METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE);
    Optional<Sha1HashCode> sha1 = onDiskBuildInfo.getHash(METADATA_KEY_FOR_R_DOT_TXT_SHA1);
    Preconditions.checkState(rDotPackage.isPresent());
    Preconditions.checkState(sha1.isPresent());
    return new BuildOutput(rDotPackage.get(), sha1.get());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return outputInitializer;
  }

  static class BuildOutput {
    private final String rDotJavaPackage;
    private final Sha1HashCode rDotTxtHash;

    BuildOutput(String rDotJavaPackage, Sha1HashCode rDotTxtHash) {
      this.rDotJavaPackage = rDotJavaPackage;
      this.rDotTxtHash = rDotTxtHash;
    }
  }

  @Override
  @Nullable
  public Path getPathToOutput() {
    return null;
  }

  Path getPathToClassesJar() {
    return uberClassesJar;
  }

  SourcePath getResDirectory() {
    return new BuildTargetSourcePath(getBuildTarget(), unpackDirectory.resolve("res"));
  }

  String getRDotJavaPackage() {
    return outputInitializer.getBuildOutput().rDotJavaPackage;
  }

  Path getTextSymbolsFile() {
    return unpackDirectory.resolve("R.txt");
  }

  Sha1HashCode getTextSymbolsHash() {
    return outputInitializer.getBuildOutput().rDotTxtHash;
  }

  SourcePath getAssetsDirectory() {
    return new BuildTargetSourcePath(getBuildTarget(), unpackDirectory.resolve("assets"));
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
