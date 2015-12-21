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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;

/**
 * A composite step used to compile java libraries directly to jar files retaining the intermediate
 * .class files in memory.
 */
public class JavacDirectToJarStep implements Step {

  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final BuildTarget invokingRule;
  private final SourcePathResolver resolver;
  private final ProjectFilesystem filesystem;
  private final ImmutableSortedSet<Path> declaredClasspathEntries;
  private final Path outputDirectory;
  private final JavacOptions buildTimeOptions;
  private final Optional<Path> workingDirectory;
  private final Optional<Path> pathToSrcsList;
  private final Optional<SuggestBuildRules> suggestBuildRules;
  private final ImmutableSortedSet<Path> entriesToJar;
  private final Optional<String> mainClass;
  private final Optional<Path> manifestFile;
  private final Path outputJar;

  @Nullable
  private JavaInMemoryFileManager inMemoryFileManager;

  public JavacDirectToJarStep(
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      JavacOptions buildTimeOptions,
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Optional<Path> pathToSrcsList,
      Optional<SuggestBuildRules> suggestBuildRules,
      ImmutableSortedSet<Path> entriesToJar,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      Path outputJar) {
    this.sourceFilePaths = sourceFilePaths;
    this.invokingRule = invokingRule;
    this.resolver = resolver;
    this.filesystem = filesystem;
    this.declaredClasspathEntries = declaredClasspathEntries;
    this.buildTimeOptions = buildTimeOptions;
    this.outputDirectory = outputDirectory;
    this.workingDirectory = workingDirectory;
    this.pathToSrcsList = pathToSrcsList;
    this.suggestBuildRules = suggestBuildRules;
    this.entriesToJar = entriesToJar;
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
    this.outputJar = outputJar;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {

    CustomZipOutputStream jarOutputStream = null;

    try {

      jarOutputStream = ZipOutputStreams.newOutputStream(
          filesystem.getPathForRelativePath(outputJar),
          ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP);

      JavacStep javacStep = createJavacStep(jarOutputStream);

      int javacStepResult = javacStep.execute(context);

      if (javacStepResult != 0) {
        return javacStepResult;
      }

      // entriesToJar is the output directory which normally contains .class files that are to be
      // added into the jarOutputStream. However, in this step they are already directly placed in
      // jarOutputStream by the compiler. entriesToJar is still needed though because it may contain
      // other resources that need to be copied into the jar.
      return JarDirectoryStepHelper.createJarFile(
          filesystem,
          outputJar,
          jarOutputStream,
          ImmutableSortedSet.copyOf(entriesToJar),
          inMemoryFileManager != null
              ? inMemoryFileManager.getEntries()
              : ImmutableSet.<String>of(),
          mainClass,
          manifestFile,
          /* mergeManifests */ true,
          ImmutableSet.<Pattern>of(),
          context);

    } finally {
      if (jarOutputStream != null) {
        jarOutputStream.close();
      }
    }
  }

  @Override
  public String getShortName() {
    return "javac_jar";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList<String> javacStepOptions = JavacStep.getOptions(
        buildTimeOptions,
        filesystem,
        outputDirectory,
        context,
        declaredClasspathEntries);
    String javacDescription = buildTimeOptions.getJavac().getDescription(
        javacStepOptions,
        sourceFilePaths,
        pathToSrcsList);

    String jarDescription = String.format("jar %s %s %s %s",
        getJarArgs(),
        outputJar,
        manifestFile.isPresent() ? manifestFile.get() : "",
        Joiner.on(' ').join(entriesToJar));

    return javacDescription + "; " + jarDescription;
  }

  private String getJarArgs() {
    String result = "cf";
    if (manifestFile.isPresent()) {
      result += "m";
    }
    return result;
  }

  private JavacStep createJavacStep(CustomZipOutputStream jarOutputStream) {
    return new JavacStep(
        outputDirectory,
        Optional.of(createFileManagerFactory(jarOutputStream)),
        workingDirectory,
        sourceFilePaths,
        pathToSrcsList,
        declaredClasspathEntries,
        buildTimeOptions.getJavac(),
        buildTimeOptions,
        invokingRule,
        suggestBuildRules,
        resolver,
        filesystem);
  }

  private StandardJavaFileManagerFactory createFileManagerFactory(
      final CustomZipOutputStream jarOutputStream) {
    return new StandardJavaFileManagerFactory() {
      @Override
      public StandardJavaFileManager create(JavaCompiler compiler) {
        inMemoryFileManager = new JavaInMemoryFileManager(
            compiler.getStandardFileManager(null, null, null),
            jarOutputStream);
        return inMemoryFileManager;
      }
    };
  }
}
