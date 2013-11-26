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

import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;
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
public class JavacInMemoryStep implements Step {

  private final String outputDirectory;

  private final Set<String> javaSourceFilePaths;

  private final JavacOptions javacOptions;

  private final Optional<String> pathToOutputAbiFile;

  @Nullable
  private File abiKeyFile;

  @Nullable
  private Sha1HashCode abiKey;

  private final ImmutableSet<String> transitiveClasspathEntries;

  private final ImmutableSet<String> declaredClasspathEntries;

  private final Optional<String> invokingRule;

  private final BuildDependencies buildDependencies;

  private final Optional<SuggestBuildRules> suggestBuildRules;

  /**
   * Will be {@code true} once {@link #buildWithClasspath(ExecutionContext, Set)} has been invoked.
   */
  private AtomicBoolean isExecuted = new AtomicBoolean(false);

  private static final Pattern IMPORT_FAILURE =
      Pattern.compile("import ([\\w\\.\\*]*);");

  private static final Pattern PACKAGE_FAILURE =
      Pattern.compile(".*?package ([\\w\\.\\*]*) does not exist");

  private static final Pattern ACCESS_FAILURE =
      Pattern.compile(".*?error: cannot access ([\\w\\.\\*]*)");

  private static final Pattern CLASS_NOT_FOUND =
      Pattern.compile(".*?class file for ([\\w\\.\\*]*) not found");

  private static final Pattern CLASS_SYMBOL_NOT_FOUND =
      Pattern.compile(".*?symbol:\\s*class\\s*([\\w\\.\\*]*)");

  private static final ImmutableList<Pattern> MISSING_IMPORT_PATTERNS =
      ImmutableList.of(IMPORT_FAILURE,
          PACKAGE_FAILURE,
          ACCESS_FAILURE,
          CLASS_NOT_FOUND,
          CLASS_SYMBOL_NOT_FOUND);

  private static final String LINE_SEPARATOR = System.getProperty("line.separator");

  public static interface SuggestBuildRules {
    public ImmutableSet<String> suggest(ProjectFilesystem filesystem,
        ImmutableSet<String> failedImports);
  }

  public JavacInMemoryStep(
      String outputDirectory,
      Set<String> javaSourceFilePaths,
      Set<String> transitiveClasspathEntries,
      Set<String> declaredClasspathEntries,
      JavacOptions javacOptions,
      Optional<String> pathToOutputAbiFile,
      Optional<String> invokingRule,
      BuildDependencies buildDependencies,
      Optional<SuggestBuildRules> suggestBuildRules) {
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    this.javaSourceFilePaths = ImmutableSet.copyOf(javaSourceFilePaths);
    this.transitiveClasspathEntries = ImmutableSet.copyOf(transitiveClasspathEntries);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.pathToOutputAbiFile = Preconditions.checkNotNull(pathToOutputAbiFile);

    this.declaredClasspathEntries = ImmutableSet.copyOf(declaredClasspathEntries);
    this.invokingRule = Preconditions.checkNotNull(invokingRule);
    this.buildDependencies = Preconditions.checkNotNull(buildDependencies);
    this.suggestBuildRules = Preconditions.checkNotNull(suggestBuildRules);
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
    Function<String, Path> pathRelativizer = filesystem.getPathRelativizer();
    builder.add("-d").add(pathRelativizer.apply(outputDirectory).toString());

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath = Joiner.on(File.pathSeparator).join(
          Iterables.transform(buildClasspathEntries, pathRelativizer));
      builder.add("-classpath", classpath);
    } else {
      builder.add("-classpath", "''");
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

  public int executeBuild(ExecutionContext context) {
    // Build up the compilation task.
    if (buildDependencies == BuildDependencies.FIRST_ORDER_ONLY) {
      return buildWithClasspath(context,
          ImmutableSet.copyOf(declaredClasspathEntries));
    } else if (buildDependencies == BuildDependencies.WARN_ON_TRANSITIVE) {
      return tryBuildWithFirstOrderDeps(context);
    } else {
      return buildWithClasspath(context, getClasspathEntries());
    }
  }

  private int tryBuildWithFirstOrderDeps(ExecutionContext context) {
    CapturingPrintStream stdout = new CapturingPrintStream();
    CapturingPrintStream stderr = new CapturingPrintStream();
    ExecutionContext firstOrderContext = context.createSubContext(stdout, stderr);

    int declaredDepsResult = buildWithClasspath(firstOrderContext,
        ImmutableSet.copyOf(declaredClasspathEntries));

    String firstOrderStdout = stdout.getContentsAsString(Charsets.UTF_8);
    String firstOrderStderr = stderr.getContentsAsString(Charsets.UTF_8);

    if (declaredDepsResult != 0) {
      int transitiveResult = buildWithClasspath(context,
          ImmutableSet.copyOf(transitiveClasspathEntries));
      if (transitiveResult == 0) {
        ImmutableSet<String> failedImports = findFailedImports(firstOrderStderr);
        ImmutableList.Builder<String> errorMessage = ImmutableList.builder();

        errorMessage.add(String.format("Rule %s builds with its transitive " +
            "dependencies but not with its first order dependencies.", invokingRule.or("")));
        errorMessage.add("The following packages were missing:");
        errorMessage.add(Joiner.on(LINE_SEPARATOR).join(failedImports));
        if (suggestBuildRules.isPresent()) {
          errorMessage.add("Try adding the following deps:");
          errorMessage.add(Joiner.on(LINE_SEPARATOR)
              .join(suggestBuildRules.get().suggest(context.getProjectFilesystem(),
                  failedImports)));
        }
        errorMessage.add("");
        errorMessage.add("");
        context.getStdErr().println(Joiner.on("\n").join(errorMessage.build()));
      }
      return transitiveResult;
    } else {
      context.getStdOut().print(firstOrderStdout);
      context.getStdErr().print(firstOrderStderr);
    }

    return declaredDepsResult;
  }

  @VisibleForTesting
  static ImmutableSet<String> findFailedImports(String output) {
    Iterable<String> lines = Splitter.on(LINE_SEPARATOR).split(output);
    ImmutableSortedSet.Builder<String> failedImports = ImmutableSortedSet.naturalOrder();
    for (String line : lines) {
      for (Pattern missingImportPattern : MISSING_IMPORT_PATTERNS) {
        Matcher lineMatch = missingImportPattern.matcher(line);
        if (lineMatch.matches()) {
          failedImports.add(lineMatch.group(1));
          break;
        }
      }
    }
    return failedImports.build();
  }

  /**
   * @return The classpath entries used to invoke javac.
   */
  protected ImmutableSet<String> getClasspathEntries() {
    if (buildDependencies == BuildDependencies.TRANSITIVE) {
      return transitiveClasspathEntries;
    } else {
      return declaredClasspathEntries;
    }
  }

  protected int buildWithClasspath(ExecutionContext context, Set<String> buildClasspathEntries) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Preconditions.checkNotNull(compiler,
        "If using JRE instead of JDK, ToolProvider.getSystemJavaCompiler() may be null.");
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> compilationUnits;
    try {
      compilationUnits = createCompilationUnits(
          fileManager, context.getProjectFilesystem().getPathRelativizer());
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
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
      Function<String, Path> pathRelativizer) throws IOException {
    List<JavaFileObject> compilationUnits = Lists.newArrayList();
    for (String path : javaSourceFilePaths) {
      if (path.endsWith(".java")) {
        // For an ordinary .java file, create a corresponding JavaFileObject.
        Iterable<? extends JavaFileObject> javaFileObjects = fileManager.getJavaFileObjects(
            pathRelativizer.apply(path).toFile());
        compilationUnits.add(Iterables.getOnlyElement(javaFileObjects));
      } else if (path.endsWith(".src.zip")) {
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ZipFile zipFile = new ZipFile(pathRelativizer.apply(path).toFile());
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

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder builder = new StringBuilder("javac ");
    Joiner.on(" ").appendTo(builder, getOptions(context, getClasspathEntries()));
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, javaSourceFilePaths);

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return "javac";
  }

  @VisibleForTesting
  Set<String> getSrcs() {
    return javaSourceFilePaths;
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
