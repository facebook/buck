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

package com.facebook.buck.java;

import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class JavacInMemoryStep implements Step {

  private final String pathToOutputDirectory;

  private final Set<String> javaSourceFilePaths;

  private final ImmutableSet<String> classpathEntries;

  private final JavacOptions javacOptions;

  private final Optional<String> pathToOutputAbiFile;

  @Nullable
  private File abiKeyFile;

  @Nullable
  private Sha1HashCode abiKey;

  /**
   * Will be {@code true} once {@link #buildWithClasspath(ExecutionContext, Set)} has been invoked.
   */
  private AtomicBoolean isExecuted = new AtomicBoolean(false);

  public JavacInMemoryStep(
        String pathToOutputDirectory,
        Set<String> javaSourceFilePaths,
        Set<String> classpathEntries,
        JavacOptions javacOptions,
        Optional<String> pathToOutputAbiFile) {
    Preconditions.checkNotNull(pathToOutputDirectory);
    this.pathToOutputDirectory = pathToOutputDirectory;
    this.javaSourceFilePaths = ImmutableSet.copyOf(javaSourceFilePaths);
    this.classpathEntries = ImmutableSet.copyOf(classpathEntries);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.pathToOutputAbiFile = Preconditions.checkNotNull(pathToOutputAbiFile);
  }

  /**
   * Returns a list of command-line options to pass to javac.  These options reflect
   * the configuration of this javac command.
   *
   * @param context the ExecutionContext with in which javac will run
   * @return list of String command-line options.
   */
  @VisibleForTesting
  protected ImmutableList<String> getOptions(ExecutionContext context,
      Set<String> buildClasspathEntries) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    ProjectFilesystem filesystem = context.getProjectFilesystem();
    AnnotationProcessingDataDecorator decorator;
    if (pathToOutputAbiFile.isPresent()) {
      abiKeyFile = filesystem.getFileForRelativePath(pathToOutputAbiFile.get());
      decorator = new AbiWritingAnnotationProcessingDataDecorator(abiKeyFile);
    } else {
      decorator = AnnotationProcessingDataDecorators.identity();
    }
    javacOptions.appendOptionsToList(builder,
        context.getProjectFilesystem().getPathRelativizer(),
        decorator);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-verbose");
    }

    // Specify the output directory.
    Function<String, String> pathRelativizer = filesystem.getPathRelativizer();
    builder.add("-d").add(pathRelativizer.apply(pathToOutputDirectory));

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath = Joiner.on(File.pathSeparator).join(
          Iterables.transform(buildClasspathEntries, pathRelativizer));
      builder.add("-classpath", classpath);
    }

    return builder.build();
  }

  @Override
  public final int execute(ExecutionContext context) {
    try {
      return executeBuild(context);
    } finally {
      isExecuted.set(true);
    }
  }

  protected int executeBuild(ExecutionContext context) {
    return buildWithClasspath(context, ImmutableSet.copyOf(classpathEntries));
  }

  protected int buildWithClasspath(ExecutionContext context, Set<String> buildClasspathEntries) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Preconditions.checkNotNull(compiler,
        "If using JRE instead of JDK, ToolProvider.getSystemJavaCompiler() may be null.");
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
    List<String> options = getOptions(context, buildClasspathEntries);
    List<String> classNamesForAnnotationProcessing = ImmutableList.of();
    Iterable<? extends JavaFileObject> compilationUnits =
        fileManager.getJavaFileObjectsFromStrings(
            Iterables.transform(javaSourceFilePaths,
                                context.getProjectFilesystem().getPathRelativizer()));

    Writer compilerOutputWriter = new PrintWriter(context.getStdErr());
    JavaCompiler.CompilationTask compilationTask = compiler.getTask(
        compilerOutputWriter,
        fileManager,
        diagnostics,
        options,
        classNamesForAnnotationProcessing,
        compilationUnits);

    // Invoke the compilation and inspect the result.
    boolean isSuccess = compilationTask.call();
    if (isSuccess) {
      if (abiKeyFile != null) {
        try {
          String firstLine = Files.readFirstLine(abiKeyFile, Charsets.UTF_8);
          if (firstLine != null) {
            abiKey = new Sha1HashCode(firstLine);
          }
        } catch (IOException e) {
          e.printStackTrace(context.getStdErr());
          return 1;
        }
      }
      return 0;
    } else {
      if (context.getVerbosity().shouldPrintStandardInformation()) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
          context.getStdErr().println(diagnostic);
        }
      }
      return 1;
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    Set<String> buildClassPathEntries = classpathEntries;

    StringBuilder builder = new StringBuilder("javac ");
    Joiner.on(" ").appendTo(builder, getOptions(context, buildClassPathEntries));
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, javaSourceFilePaths);

    return builder.toString();
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return String.format("javac %s", pathToOutputDirectory);
  }

  public Set<String> getSrcs() {
    return javaSourceFilePaths;
  }

  protected ImmutableSet<String> getClasspathEntries() {
    return classpathEntries;
  }

  /**
   * Returns a SHA-1 hash for the ABI of the Java code compiled by this step.
   * <p>
   * In order for this method to return a non-null value, it must be invoked after
   * {@link #buildWithClasspath(ExecutionContext, Set)}, which must have completed successfully
   * (i.e., returned with an exit code of 0).
   */
  @Nullable
  public Sha1HashCode getAbiKey() {
    Preconditions.checkState(isExecuted.get(), "Must execute step before requesting AbiKey.");
    // Note that if the rule fails, isExecuted should still be set, but abiKey will be null.
    return abiKey;
  }
}
