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

import com.facebook.buck.event.MissingSymbolEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Command used to compile java libraries with a variety of ways to handle dependencies.
 * <p>
 * If {@code buildDependencies} is set to {@link BuildDependencies#FIRST_ORDER_ONLY}, this class
 * will invoke javac using {@code declaredClasspathEntries} for the classpath.
 * If {@code buildDependencies} is set to {@link BuildDependencies#TRANSITIVE}, this class will
 * invoke javac using {@code transitiveClasspathEntries} for the classpath.
 * If {@code buildDependencies} is set to {@link BuildDependencies#WARN_ON_TRANSITIVE}, this class
 * will first compile using {@code declaredClasspathEntries}, and should that fail fall back to
 * {@code transitiveClasspathEntries} but warn the developer about which dependencies were in
 * the transitive classpath but not in the declared classpath.
 */
public class JavacInMemoryStep extends JavacStep {

  public JavacInMemoryStep(
      Path outputDirectory,
      Set<? extends SourcePath> javaSourceFilePaths,
      Set<Path> transitiveClasspathEntries,
      Set<Path> declaredClasspathEntries,
      JavacOptions javacOptions,
      Optional<Path> pathToOutputAbiFile,
      Optional<BuildTarget> invokingRule,
      BuildDependencies buildDependencies,
      Optional<SuggestBuildRules> suggestBuildRules,
      Optional<Path> pathToSrcsList) {
    super(outputDirectory,
        javaSourceFilePaths,
        transitiveClasspathEntries,
        declaredClasspathEntries,
        javacOptions,
        pathToOutputAbiFile,
        invokingRule,
        buildDependencies,
        suggestBuildRules,
        pathToSrcsList);
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder builder = new StringBuilder("javac ");
    Joiner.on(" ").appendTo(builder, getOptions(context, getClasspathEntries()));
    builder.append(" ");

    if (pathToSrcsList.isPresent()) {
      builder.append("@").append(pathToSrcsList.get());
    } else {
      Joiner.on(" ").appendTo(builder, javaSourceFilePaths);
    }

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return "javac";
  }

  @Override
  protected int buildWithClasspath(ExecutionContext context, Set<Path> buildClasspathEntries) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Preconditions.checkNotNull(compiler,
        "If using JRE instead of JDK, ToolProvider.getSystemJavaCompiler() may be null.");
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> compilationUnits;
    try {
      compilationUnits = createCompilationUnits(
          fileManager, context.getProjectFilesystem().getAbsolutifier());
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }

    if (pathToSrcsList.isPresent()) {
      // write javaSourceFilePaths to classes file
      // for buck user to have a list of all .java files to be compiled
      // since we do not print them out to console in case of error
      try {
        context.getProjectFilesystem().writeLinesToPath(
            Iterables.transform(javaSourceFilePaths, Functions.toStringFunction()),
            pathToSrcsList.get());
      } catch (IOException e) {
        context.logError(e,
            "Cannot write list of .java files to compile to %s file! Terminating compilation.",
            pathToSrcsList.get());
        return 1;
      }
    }

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
    List<String> options = getOptions(context, buildClasspathEntries);
    List<String> classNamesForAnnotationProcessing = ImmutableList.of();
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
        int numErrors = 0;
        int numWarnings = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
          Diagnostic.Kind kind = diagnostic.getKind();
          if (kind == Diagnostic.Kind.ERROR) {
            ++numErrors;
            handleMissingSymbolError(diagnostic, context);
          } else if (kind == Diagnostic.Kind.WARNING || kind == Diagnostic.Kind.MANDATORY_WARNING) {
            ++numWarnings;
          }

          context.getStdErr().println(diagnostic);
        }

        if (numErrors > 0 || numWarnings > 0) {
          context.getStdErr().printf("Errors: %d. Warnings: %d.\n", numErrors, numWarnings);
        }
      }
      return 1;
    }
  }

  private Iterable<? extends JavaFileObject> createCompilationUnits(
      StandardJavaFileManager fileManager,
      Function<Path, Path> absolutifier) throws IOException {
    List<JavaFileObject> compilationUnits = Lists.newArrayList();
    for (SourcePath srcPath : javaSourceFilePaths) {
      Path path = srcPath.resolve();

      if (path.toString().endsWith(".java")) {
        // For an ordinary .java file, create a corresponding JavaFileObject.
        Iterable<? extends JavaFileObject> javaFileObjects = fileManager.getJavaFileObjects(
            absolutifier.apply(path).toFile());
        compilationUnits.add(Iterables.getOnlyElement(javaFileObjects));
      } else if (path.toString().endsWith(SRC_ZIP)) {
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ZipFile zipFile = new ZipFile(absolutifier.apply(path).toFile());
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries();
             entries.hasMoreElements();
            ) {
          ZipEntry entry = entries.nextElement();
          if (!entry.getName().endsWith(".java")) {
            continue;
          }

          compilationUnits.add(new ZipEntryJavaFileObject(zipFile, entry));
        }
      }
    }
    return compilationUnits;
  }

  private void handleMissingSymbolError(
      Diagnostic<? extends JavaFileObject> diagnostic,
      ExecutionContext context) {
    if (!invokingRule.isPresent()) {
      // This compile isn't associated with any rule, so don't bother reporting missing symbols.
      return;
    }
    JavacErrorParser javacErrorParser = new JavacErrorParser(
        context.getProjectFilesystem(),
        context.getJavaPackageFinder());
    Optional<String> symbol =
        javacErrorParser.getMissingSymbolFromCompilerError(diagnostic.toString());
    if (!symbol.isPresent()) {
      // This error wasn't related to a missing symbol, as far as we can tell.
      return;
    }
    MissingSymbolEvent event = MissingSymbolEvent.create(
        invokingRule.get(),
        symbol.get(),
        MissingSymbolEvent.SymbolType.Java);
    context.getBuckEventBus().post(event);
  }
}
