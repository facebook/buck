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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavacEventSinkToBuckEventBusBridge;
import com.facebook.buck.jvm.java.LoggingJarBuilderObserver;
import com.facebook.buck.jvm.java.RemoveClassesPatternsMatcher;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.zip.JarBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class UnzipAar extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements InitializableFromDisk<UnzipAar.BuildOutput> {

  private static final String AAR_UNZIP_PATH_FORMAT = "__unpack_%s__";

  @AddToRuleKey private final SourcePath aarFile;
  private final Path unpackDirectory;
  private final Path uberClassesJar;
  private final Path pathToTextSymbolsDir;
  private final Path pathToTextSymbolsFile;
  private final Path pathToRDotJavaPackageFile;
  private final BuildOutputInitializer<BuildOutput> outputInitializer;

  UnzipAar(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePath aarFile) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.aarFile = aarFile;
    this.unpackDirectory =
        BuildTargetPaths.getScratchPath(getProjectFilesystem(), buildTarget, AAR_UNZIP_PATH_FORMAT);
    this.uberClassesJar =
        BuildTargetPaths.getScratchPath(
            getProjectFilesystem(), buildTarget, "__uber_classes_%s__/classes.jar");
    pathToTextSymbolsDir =
        BuildTargetPaths.getGenPath(getProjectFilesystem(), buildTarget, "__%s_text_symbols__");
    pathToTextSymbolsFile = pathToTextSymbolsDir.resolve("R.txt");
    pathToRDotJavaPackageFile = pathToTextSymbolsDir.resolve("RDotJavaPackage.txt");
    this.outputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), unpackDirectory)));
    steps.add(
        new UnzipStep(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(aarFile),
            unpackDirectory,
            Optional.empty()));

    steps.add(new TouchStep(getProjectFilesystem(), getProguardConfig()));
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                context.getSourcePathResolver().getRelativePath(getResDirectory()))));
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                context.getSourcePathResolver().getRelativePath(getAssetsDirectory()))));
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getNativeLibsDirectory())));
    steps.add(new TouchStep(getProjectFilesystem(), getTextSymbolsFile()));

    // We take the classes.jar file that is required to exist in an .aar and merge it with any
    // .jar files under libs/ into an "uber" jar. We do this for simplicity because we do not know
    // how many entries there are in libs/ at graph enhancement time, but we need to make sure
    // that all of the .class files in the .aar get packaged. As it is implemented today, an
    // android_library that depends on an android_prebuilt_aar can compile against anything in the
    // .aar's classes.jar or libs/.
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                uberClassesJar.getParent())));
    steps.add(
        new AbstractExecutionStep("create_uber_classes_jar") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            Path libsDirectory = unpackDirectory.resolve("libs");
            boolean dirDoesNotExistOrIsEmpty;
            ProjectFilesystem filesystem = getProjectFilesystem();
            if (!filesystem.exists(libsDirectory)) {
              dirDoesNotExistOrIsEmpty = true;
            } else {
              dirDoesNotExistOrIsEmpty = filesystem.getDirectoryContents(libsDirectory).isEmpty();
            }

            Path classesJar = unpackDirectory.resolve("classes.jar");
            JavacEventSinkToBuckEventBusBridge eventSink =
                new JavacEventSinkToBuckEventBusBridge(context.getBuckEventBus());
            if (!filesystem.exists(classesJar)) {
              new JarBuilder()
                  .setObserver(new LoggingJarBuilderObserver(eventSink))
                  .createJarFile(filesystem.resolve(classesJar));
            }

            if (dirDoesNotExistOrIsEmpty) {
              filesystem.copy(classesJar, uberClassesJar, CopySourceMode.FILE);
            } else {
              // Glob all of the contents from classes.jar and the entries in libs/ into a single
              // JAR.
              ImmutableSortedSet.Builder<Path> entriesToJarBuilder =
                  ImmutableSortedSet.naturalOrder();
              entriesToJarBuilder.add(classesJar);
              entriesToJarBuilder.addAll(
                  getProjectFilesystem().getDirectoryContents(libsDirectory));

              ImmutableSortedSet<Path> entriesToJar = entriesToJarBuilder.build();

              new JarBuilder()
                  .setObserver(new LoggingJarBuilderObserver(eventSink))
                  .setEntriesToJar(entriesToJar.stream().map(filesystem::resolve))
                  .setMainClass(Optional.<String>empty().orElse(null))
                  .setManifestFile(Optional.<Path>empty().orElse(null))
                  .setShouldMergeManifests(true)
                  .setRemoveEntryPredicate(RemoveClassesPatternsMatcher.EMPTY)
                  .createJarFile(filesystem.resolve(uberClassesJar));
            }
            return StepExecutionResults.SUCCESS;
          }
        });

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToTextSymbolsDir)));
    steps.add(
        new ExtractFromAndroidManifestStep(
            getAndroidManifest(), getProjectFilesystem(), pathToRDotJavaPackageFile));
    steps.add(
        CopyStep.forFile(getProjectFilesystem(), getTextSymbolsFile(), pathToTextSymbolsFile));

    buildableContext.recordArtifact(unpackDirectory);
    buildableContext.recordArtifact(uberClassesJar);
    buildableContext.recordArtifact(pathToTextSymbolsFile);
    buildableContext.recordArtifact(pathToRDotJavaPackageFile);
    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(SourcePathResolver pathResolver) {
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

  public static String getAarUnzipPathFormat() {
    return AAR_UNZIP_PATH_FORMAT;
  }

  static class BuildOutput {
    private final String rDotJavaPackage;

    BuildOutput(String rDotJavaPackage) {
      this.rDotJavaPackage = rDotJavaPackage;
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), unpackDirectory);
  }

  Path getPathToClassesJar() {
    return uberClassesJar;
  }

  SourcePath getResDirectory() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), unpackDirectory.resolve("res"));
  }

  String getRDotJavaPackage() {
    return outputInitializer.getBuildOutput().rDotJavaPackage;
  }

  Path getTextSymbolsFile() {
    return unpackDirectory.resolve("R.txt");
  }

  SourcePath getAssetsDirectory() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), unpackDirectory.resolve("assets"));
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
