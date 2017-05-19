/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.jvm.java.JavacEventSinkToBuckEventBusBridge;
import com.facebook.buck.jvm.java.LoggingJarBuilderObserver;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.zip.JarBuilder;
import com.facebook.buck.zip.UnzipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class UnzipAar extends AbstractBuildRule
    implements InitializableFromDisk<UnzipAar.BuildOutput> {

  private static final String METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE = "R_DOT_JAVA_PACKAGE";

  @AddToRuleKey private final SourcePath aarFile;
  private final Path unpackDirectory;
  private final Path uberClassesJar;
  private final Path pathToTextSymbolsDir;
  private final Path pathToTextSymbolsFile;
  private final Path pathToRDotJavaPackageFile;
  private final BuildOutputInitializer<BuildOutput> outputInitializer;

  UnzipAar(BuildRuleParams buildRuleParams, SourcePath aarFile) {
    super(buildRuleParams);
    this.aarFile = aarFile;
    BuildTarget buildTarget = buildRuleParams.getBuildTarget();
    this.unpackDirectory =
        BuildTargets.getScratchPath(getProjectFilesystem(), buildTarget, "__unpack_%s__");
    this.uberClassesJar =
        BuildTargets.getScratchPath(
            getProjectFilesystem(), buildTarget, "__uber_classes_%s__/classes.jar");
    pathToTextSymbolsDir =
        BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, "__%s_text_symbols__");
    pathToTextSymbolsFile = pathToTextSymbolsDir.resolve("R.txt");
    pathToRDotJavaPackageFile = pathToTextSymbolsDir.resolve("RDotJavaPackage.txt");
    this.outputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), unpackDirectory));
    steps.add(
        new UnzipStep(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(aarFile),
            unpackDirectory));
    steps.add(new TouchStep(getProjectFilesystem(), getProguardConfig()));
    steps.add(
        MkdirStep.of(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(getAssetsDirectory())));
    steps.add(MkdirStep.of(getProjectFilesystem(), getNativeLibsDirectory()));
    steps.add(new TouchStep(getProjectFilesystem(), getTextSymbolsFile()));

    // We take the classes.jar file that is required to exist in an .aar and merge it with any
    // .jar files under libs/ into an "uber" jar. We do this for simplicity because we do not know
    // how many entries there are in libs/ at graph enhancement time, but we need to make sure
    // that all of the .class files in the .aar get packaged. As it is implemented today, an
    // android_library that depends on an android_prebuilt_aar can compile against anything in the
    // .aar's classes.jar or libs/.
    steps.add(MkdirStep.of(getProjectFilesystem(), uberClassesJar.getParent()));
    steps.add(
        new AbstractExecutionStep("create_uber_classes_jar") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) {
            Path libsDirectory = unpackDirectory.resolve("libs");
            boolean dirDoesNotExistOrIsEmpty;
            ProjectFilesystem filesystem = getProjectFilesystem();
            if (!filesystem.exists(libsDirectory)) {
              dirDoesNotExistOrIsEmpty = true;
            } else {
              try {
                dirDoesNotExistOrIsEmpty = filesystem.getDirectoryContents(libsDirectory).isEmpty();
              } catch (IOException e) {
                context.logError(e, "Failed to get directory contents of %s", libsDirectory);
                return StepExecutionResult.ERROR;
              }
            }

            Path classesJar = unpackDirectory.resolve("classes.jar");
            JavacEventSinkToBuckEventBusBridge eventSink =
                new JavacEventSinkToBuckEventBusBridge(context.getBuckEventBus());
            if (!filesystem.exists(classesJar)) {
              try {
                new JarBuilder()
                    .setObserver(new LoggingJarBuilderObserver(eventSink))
                    .createJarFile(filesystem.resolve(classesJar));
              } catch (IOException e) {
                context.logError(e, "Failed to create empty jar %s", classesJar);
                return StepExecutionResult.ERROR;
              }
            }

            if (dirDoesNotExistOrIsEmpty) {
              try {
                filesystem.copy(classesJar, uberClassesJar, ProjectFilesystem.CopySourceMode.FILE);
              } catch (IOException e) {
                context.logError(e, "Failed to copy from %s to %s", classesJar, uberClassesJar);
                return StepExecutionResult.ERROR;
              }
            } else {
              // Glob all of the contents from classes.jar and the entries in libs/ into a single JAR.
              ImmutableSortedSet.Builder<Path> entriesToJarBuilder =
                  ImmutableSortedSet.naturalOrder();
              entriesToJarBuilder.add(classesJar);
              try {
                entriesToJarBuilder.addAll(
                    getProjectFilesystem().getDirectoryContents(libsDirectory));
              } catch (IOException e) {
                context.logError(e, "Failed to get directory contents of %s", libsDirectory);
                return StepExecutionResult.ERROR;
              }

              ImmutableSortedSet<Path> entriesToJar = entriesToJarBuilder.build();
              try {

                new JarBuilder()
                    .setObserver(new LoggingJarBuilderObserver(eventSink))
                    .setEntriesToJar(entriesToJar.stream().map(filesystem::resolve))
                    .setMainClass(Optional.<String>empty().orElse(null))
                    .setManifestFile(Optional.<Path>empty().orElse(null))
                    .setShouldMergeManifests(true)
                    .setEntryPatternBlacklist(ImmutableSet.of())
                    .createJarFile(filesystem.resolve(uberClassesJar));
              } catch (IOException e) {
                context.logError(e, "Failed to jar %s into %s", entriesToJar, uberClassesJar);
                return StepExecutionResult.ERROR;
              }
            }
            return StepExecutionResult.SUCCESS;
          }
        });

    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToTextSymbolsDir));
    steps.add(
        new ExtractFromAndroidManifestStep(
            getAndroidManifest(),
            getProjectFilesystem(),
            buildableContext,
            METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE,
            pathToRDotJavaPackageFile));
    steps.add(
        CopyStep.forFile(getProjectFilesystem(), getTextSymbolsFile(), pathToTextSymbolsFile));

    buildableContext.recordArtifact(unpackDirectory);
    buildableContext.recordArtifact(uberClassesJar);
    buildableContext.recordArtifact(pathToTextSymbolsFile);
    buildableContext.recordArtifact(pathToRDotJavaPackageFile);
    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    String rDotJavaPackageFromFile =
        getProjectFilesystem().readFirstLine(pathToRDotJavaPackageFile).get();
    return new BuildOutput(rDotJavaPackageFromFile);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return outputInitializer;
  }

  public Path getPathToRDotJavaPackageFile() {
    return pathToRDotJavaPackageFile;
  }

  static class BuildOutput {
    private final String rDotJavaPackage;

    BuildOutput(String rDotJavaPackage) {
      this.rDotJavaPackage = rDotJavaPackage;
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), pathToTextSymbolsDir);
  }

  Path getPathToClassesJar() {
    return uberClassesJar;
  }

  SourcePath getResDirectory() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), unpackDirectory.resolve("res"));
  }

  String getRDotJavaPackage() {
    return outputInitializer.getBuildOutput().rDotJavaPackage;
  }

  Path getTextSymbolsFile() {
    return unpackDirectory.resolve("R.txt");
  }

  SourcePath getAssetsDirectory() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), unpackDirectory.resolve("assets"));
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
