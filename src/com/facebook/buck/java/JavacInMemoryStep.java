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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class JavacInMemoryStep implements Step {

  private final File outputDirectory;

  private final Set<String> javaSourceFilePaths;

  protected final ImmutableSet<String> classpathEntries;
  private final JavacOptions javacOptions;

  public JavacInMemoryStep(
        String outputDirectory,
        Set<String> javaSourceFilePaths,
        Set<String> classpathEntries,
        JavacOptions javacOptions) {
    Preconditions.checkNotNull(outputDirectory);
    this.outputDirectory = new File(outputDirectory);
    this.javaSourceFilePaths = ImmutableSet.copyOf(javaSourceFilePaths);
    this.classpathEntries = ImmutableSet.copyOf(classpathEntries);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
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

    javacOptions.appendOptionsToList(builder);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-verbose");
    }

    // Specify the output directory.
    builder.add("-d").add(outputDirectory.getAbsolutePath());

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath = Joiner.on(File.pathSeparator).join(buildClasspathEntries);
      builder.add("-classpath", classpath);
    }

    return builder.build();
  }

  @Override
  public final int execute(ExecutionContext context) {
    return executeBuild(context);
  }

  protected int executeBuild(ExecutionContext context) {
    return buildWithClasspath(context, ImmutableSet.copyOf(classpathEntries));
  }

  protected int buildWithClasspath(ExecutionContext context,
      Set<String> buildClasspathEntries) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Preconditions.checkNotNull(compiler,
        "If using JRE instead of JDK, ToolProvider.getSystemJavaCompiler() may be null.");
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
    List<String> options = getOptions(context, buildClasspathEntries);
    List<String> classNamesForAnnotationProcessing = ImmutableList.of();
    Iterable<? extends JavaFileObject> compilationUnits =
        fileManager.getJavaFileObjectsFromStrings(javaSourceFilePaths);

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
    return String.format("javac %s", outputDirectory);
  }

  public Set<String> getSrcs() {
    return javaSourceFilePaths;
  }
}
