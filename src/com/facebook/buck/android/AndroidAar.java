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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.jvm.java.HasClasspathEntries;
import com.facebook.buck.jvm.java.JarDirectoryStep;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public class AndroidAar extends AbstractBuildRule implements HasClasspathEntries {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, PACKAGING);
  public static final String AAR_FORMAT = "%s.aar";

  private final Path pathToOutputFile;
  private final Path temp;
  private final AndroidManifest manifest;
  private final AndroidResource androidResource;
  private final SourcePath assembledResourceDirectory;
  private final SourcePath assembledAssetsDirectory;
  private final Optional<Path> assembledNativeLibs;
  private final ImmutableSet<SourcePath> nativeLibAssetsDirectories;
  private final ImmutableSortedSet<SourcePath> classpathsToIncludeInJar;

  public AndroidAar(
      BuildRuleParams params,
      AndroidManifest manifest,
      AndroidResource androidResource,
      SourcePath assembledResourceDirectory,
      SourcePath assembledAssetsDirectory,
      Optional<Path> assembledNativeLibs,
      ImmutableSet<SourcePath> nativeLibAssetsDirectories,
      ImmutableSortedSet<SourcePath> classpathsToIncludeInJar) {
    super(params);
    BuildTarget buildTarget = params.getBuildTarget();
    this.pathToOutputFile =
        BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, AAR_FORMAT);
    this.temp = BuildTargets.getScratchPath(getProjectFilesystem(), buildTarget, "__temp__%s");
    this.manifest = manifest;
    this.androidResource = androidResource;
    this.assembledAssetsDirectory = assembledAssetsDirectory;
    this.assembledResourceDirectory = assembledResourceDirectory;
    this.assembledNativeLibs = assembledNativeLibs;
    this.nativeLibAssetsDirectories = nativeLibAssetsDirectories;
    this.classpathsToIncludeInJar = classpathsToIncludeInJar;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Create temp folder to store the files going to be zipped
    commands.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), temp));

    // Remove the output .aar file
    commands.add(RmStep.of(getProjectFilesystem(), pathToOutputFile));

    // put manifest into tmp folder
    commands.add(
        CopyStep.forFile(
            getProjectFilesystem(),
            context.getSourcePathResolver().getRelativePath(manifest.getSourcePathToOutput()),
            temp.resolve("AndroidManifest.xml")));

    // put R.txt into tmp folder
    commands.add(
        CopyStep.forFile(
            getProjectFilesystem(),
            context
                .getSourcePathResolver()
                .getAbsolutePath(
                    Preconditions.checkNotNull(androidResource.getPathToTextSymbolsFile())),
            temp.resolve("R.txt")));

    // put res/ and assets/ into tmp folder
    commands.add(
        CopyStep.forDirectory(
            getProjectFilesystem(),
            context.getSourcePathResolver().getRelativePath(assembledResourceDirectory),
            temp.resolve("res"),
            CopyStep.DirectoryMode.CONTENTS_ONLY));
    commands.add(
        CopyStep.forDirectory(
            getProjectFilesystem(),
            context.getSourcePathResolver().getRelativePath(assembledAssetsDirectory),
            temp.resolve("assets"),
            CopyStep.DirectoryMode.CONTENTS_ONLY));

    // Create our Uber-jar, and place it in the tmp folder.
    commands.add(
        new JarDirectoryStep(
            getProjectFilesystem(),
            temp.resolve("classes.jar"),
            context.getSourcePathResolver().getAllAbsolutePaths(classpathsToIncludeInJar),
            /* mainClass */ null,
            /* manifestFile */ null));

    // move native libs into tmp folder under jni/
    if (assembledNativeLibs.isPresent()) {
      commands.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              assembledNativeLibs.get(),
              temp.resolve("jni"),
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    // move native assets into tmp folder under assets/lib/
    for (SourcePath dir : nativeLibAssetsDirectories) {
      CopyNativeLibraries.copyNativeLibrary(
          getProjectFilesystem(),
          context.getSourcePathResolver().getAbsolutePath(dir),
          temp.resolve("assets").resolve("lib"),
          ImmutableSet.of(),
          commands);
    }

    // do the zipping
    commands.add(MkdirStep.of(getProjectFilesystem(), pathToOutputFile.getParent()));
    commands.add(
        new ZipStep(
            getProjectFilesystem(),
            pathToOutputFile,
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT_COMPRESSION_LEVEL,
            temp));

    buildableContext.recordArtifact(pathToOutputFile);
    return commands.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), pathToOutputFile);
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
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
}
