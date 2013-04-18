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

package com.facebook.buck.shell;

import com.facebook.buck.model.AnnotationProcessingData;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
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

public class JavacInMemoryCommand implements Command {

  private final File outputDirectory;

  private final Set<String> javaSourceFilePaths;

  private final Set<String> classpathEntries;

  private final Supplier<String> bootclasspathSupplier;

  private final AnnotationProcessingData annotationProcessingData;

  private final String sourceLevel;
  private final String targetLevel;

  public JavacInMemoryCommand(
      String outputDirectory,
      Set<String> javaSourceFilePaths,
      Set<String> classpathEntries,
      Supplier<String> bootclasspathSupplier,
      AnnotationProcessingData annotationProcessingData) {

    this(
      outputDirectory,
      javaSourceFilePaths,
      classpathEntries,
      bootclasspathSupplier,
      annotationProcessingData,
      JavacOptionsUtil.DEFAULT_SOURCE_LEVEL,
      JavacOptionsUtil.DEFAULT_TARGET_LEVEL);
  }

    public JavacInMemoryCommand(
      String outputDirectory,
      Set<String> javaSourceFilePaths,
      Set<String> classpathEntries,
      Supplier<String> bootclasspathSupplier,
      AnnotationProcessingData annotationProcessingData,
      String sourceLevel,
      String targetLevel) {
    Preconditions.checkNotNull(outputDirectory);
    this.outputDirectory = new File(outputDirectory);
    this.javaSourceFilePaths = ImmutableSet.copyOf(javaSourceFilePaths);
    this.classpathEntries = ImmutableSet.copyOf(classpathEntries);
    this.bootclasspathSupplier = Preconditions.checkNotNull(bootclasspathSupplier);
    this.annotationProcessingData = Preconditions.checkNotNull(annotationProcessingData);
    this.sourceLevel = Preconditions.checkNotNull(sourceLevel);
    this.targetLevel = Preconditions.checkNotNull(targetLevel);
  }

  /**
   * This is public for testing purposes and is not intended for use outside this class.
   *
   * Returns a list of command-line options to pass to javac.  These options reflect
   * the configuration of this javac command.
   *
   * @param context the ExecutionContext with in which javac will run
   * @return list of String command-line options.
   */
  public ImmutableList<String> getOptions(ExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    JavacOptionsUtil.addOptions(builder,
        context,
        outputDirectory,
        classpathEntries,
        bootclasspathSupplier,
        annotationProcessingData,
        sourceLevel,
        targetLevel);
    return builder.build();
  }

  @Override
  public int execute(ExecutionContext context) {

    // Build up the compilation task.
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
    List<String> options = getOptions(context);
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
    StringBuilder builder = new StringBuilder("javac ");
    Joiner.on(" ").appendTo(builder, getOptions(context));
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
