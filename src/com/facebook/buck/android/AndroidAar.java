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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.ZipStep;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Objects;
import java.util.Optional;

public class AndroidAar extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasClasspathEntries {

  public static final String AAR_FORMAT = "%s.aar";

  private final RelPath pathToOutputFile;
  private final RelPath temp;
  private final AndroidManifest manifest;
  private final AndroidResource androidResource;
  private final SourcePath assembledResourceDirectory;
  private final SourcePath assembledAssetsDirectory;
  private final Optional<SourcePath> assembledNativeLibs;
  private final Optional<SourcePath> assembledNativeLibsAssets;

  private final ImmutableSortedSet<SourcePath> classpathsToIncludeInJar;

  public AndroidAar(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      AndroidManifest manifest,
      AndroidResource androidResource,
      SourcePath assembledResourceDirectory,
      SourcePath assembledAssetsDirectory,
      Optional<SourcePath> assembledNativeLibs,
      Optional<SourcePath> assembledNativeLibsAssets,
      ImmutableSortedSet<SourcePath> classpathsToIncludeInJar) {
    super(buildTarget, projectFilesystem, params);
    this.pathToOutputFile =
        BuildTargetPaths.getGenPath(getProjectFilesystem().getBuckPaths(), buildTarget, AAR_FORMAT);
    this.temp = BuildTargetPaths.getScratchPath(getProjectFilesystem(), buildTarget, "__temp__%s");
    this.manifest = manifest;
    this.androidResource = androidResource;
    this.assembledAssetsDirectory = assembledAssetsDirectory;
    this.assembledResourceDirectory = assembledResourceDirectory;
    this.assembledNativeLibs = assembledNativeLibs;
    this.assembledNativeLibsAssets = assembledNativeLibsAssets;
    this.classpathsToIncludeInJar = classpathsToIncludeInJar;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ProjectFilesystem filesystem = getProjectFilesystem();
    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
    AbsPath buildCellRootPath = context.getBuildCellRootPath();

    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Create temp folder to store the files going to be zipped
    commands.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(buildCellRootPath, filesystem, temp)));

    // Remove the output .aar file
    commands.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildCellRootPath, filesystem, pathToOutputFile)));

    // put manifest into tmp folder
    commands.add(
        CopyStep.forFile(
            sourcePathResolver.getCellUnsafeRelPath(manifest.getSourcePathToOutput()),
            temp.resolveRel("AndroidManifest.xml")));

    // put R.txt into tmp folder
    commands.add(
        CopyStep.forFile(
            sourcePathResolver
                .getAbsolutePath(Objects.requireNonNull(androidResource.getPathToTextSymbolsFile()))
                .getPath(),
            temp.resolve("R.txt")));

    // put res/ and assets/ into tmp folder
    commands.add(
        CopyStep.forDirectory(
            sourcePathResolver.getCellUnsafeRelPath(assembledResourceDirectory),
            temp.resolveRel("res"),
            CopyStep.DirectoryMode.CONTENTS_ONLY));
    commands.add(
        CopyStep.forDirectory(
            sourcePathResolver.getCellUnsafeRelPath(assembledAssetsDirectory),
            temp.resolveRel("assets"),
            CopyStep.DirectoryMode.CONTENTS_ONLY));

    // Create our Uber-jar, and place it in the tmp folder.
    commands.add(
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(temp.resolveRel("classes.jar"))
                .setEntriesToJar(
                    sourcePathResolver.getAllRelativePaths(filesystem, classpathsToIncludeInJar))
                .setMergeManifests(true)
                .build()));

    // move native libs into tmp folder under jni/
    assembledNativeLibs.ifPresent(
        sourcePath ->
            commands.add(
                CopyStep.forDirectory(
                    sourcePathResolver.getCellUnsafeRelPath(sourcePath),
                    temp.resolveRel("jni"),
                    CopyStep.DirectoryMode.CONTENTS_ONLY)));

    // move native asset libs into tmp folder under assets/lib
    assembledNativeLibsAssets.ifPresent(
        sourcePath ->
            commands.add(
                CopyStep.forDirectory(
                    sourcePathResolver.getCellUnsafeRelPath(sourcePath),
                    temp.resolveRel("assets").resolveRel("lib"),
                    CopyStep.DirectoryMode.CONTENTS_ONLY)));

    // do the zipping
    commands.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildCellRootPath, filesystem, pathToOutputFile.getParent())));
    commands.add(
        ZipStep.of(
            filesystem,
            pathToOutputFile.getPath(),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            temp.getPath()));

    buildableContext.recordArtifact(pathToOutputFile.getPath());
    return commands.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), pathToOutputFile);
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    return classpathsToIncludeInJar;
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return JavaLibraryClasspathProvider.getClasspathDeps(getBuildDeps());
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    // The aar has no exported deps or classpath contributions of its own
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return classpathsToIncludeInJar;
  }
}
