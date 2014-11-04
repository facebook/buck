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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

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
public abstract class JavacStep implements Step {

  public static final String SRC_ZIP = ".src.zip";
  // TODO(user) s/protected/get...()/g
  protected final Path outputDirectory;

  protected final Set<Path> javaSourceFilePaths;

  protected final JavacOptions javacOptions;

  protected final Optional<Path> pathToOutputAbiFile;

  @Nullable
  protected File abiKeyFile;

  @Nullable
  protected Sha1HashCode abiKey;

  protected final ImmutableSet<Path> transitiveClasspathEntries;

  protected final ImmutableSet<Path> declaredClasspathEntries;

  protected final Optional<BuildTarget> invokingRule;

  protected final BuildDependencies buildDependencies;

  protected final Optional<SuggestBuildRules> suggestBuildRules;

  protected final Optional<Path> pathToSrcsList;

  /**
   * Will be {@code true} once {@link #buildWithClasspath(ExecutionContext, Set)} has been invoked.
   */
  protected AtomicBoolean isExecuted = new AtomicBoolean(false);

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

  /**
   * An escaper for arguments written to @argfiles.
   */
  protected static final Function<String, String> ARGFILES_ESCAPER =
      Escaper.escaper(
          Escaper.Quoter.DOUBLE,
          CharMatcher.anyOf("#\"'").or(CharMatcher.WHITESPACE));

  public static interface SuggestBuildRules {
    public ImmutableSet<String> suggest(ProjectFilesystem filesystem,
        ImmutableSet<String> failedImports);
  }

  public JavacStep(
      Path outputDirectory,
      Set<Path> javaSourceFilePaths,
      Set<Path> transitiveClasspathEntries,
      Set<Path> declaredClasspathEntries,
      JavacOptions javacOptions,
      Optional<Path> pathToOutputAbiFile,
      Optional<BuildTarget> invokingRule,
      BuildDependencies buildDependencies,
      Optional<SuggestBuildRules> suggestBuildRules,
      Optional<Path> pathToSrcsList) {
    this.outputDirectory = outputDirectory;
    this.javaSourceFilePaths = ImmutableSet.copyOf(javaSourceFilePaths);
    this.transitiveClasspathEntries = ImmutableSet.copyOf(transitiveClasspathEntries);
    this.javacOptions = javacOptions;
    this.pathToOutputAbiFile = pathToOutputAbiFile;

    this.declaredClasspathEntries = ImmutableSet.copyOf(declaredClasspathEntries);
    this.invokingRule = invokingRule;
    this.buildDependencies = buildDependencies;
    this.suggestBuildRules = suggestBuildRules;
    this.pathToSrcsList = pathToSrcsList;
  }

  @Override
  public final int execute(ExecutionContext context) throws InterruptedException {
    try {
      return executeBuild(context);
    } finally {
      isExecuted.set(true);
    }
  }

  public int executeBuild(ExecutionContext context) throws InterruptedException {
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

  private int tryBuildWithFirstOrderDeps(ExecutionContext context) throws InterruptedException {
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

        String invoker = invokingRule.isPresent() ? invokingRule.get().toString() : "";
        errorMessage.add(String.format("Rule %s builds with its transitive " +
            "dependencies but not with its first order dependencies.", invoker));
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

  protected abstract int buildWithClasspath(
      ExecutionContext context,
      Set<Path> buildClasspathEntries) throws InterruptedException;

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
   * Returns a list of command-line options to pass to javac.  These options reflect
   * the configuration of this javac command.
   *
   * @param context the ExecutionContext with in which javac will run
   * @return list of String command-line options.
   */
  @VisibleForTesting
  protected ImmutableList<String> getOptions(ExecutionContext context,
                                             Set<Path> buildClasspathEntries) {
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
        context.getProjectFilesystem().getAbsolutifier(),
        decorator);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-verbose");
    }

    // Specify the output directory.
    Function<Path, Path> pathRelativizer = filesystem.getAbsolutifier();
    builder.add("-d").add(pathRelativizer.apply(outputDirectory).toString());

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath = Joiner.on(File.pathSeparator).join(
          FluentIterable.from(buildClasspathEntries)
              .transform(pathRelativizer));
      builder.add("-classpath", classpath);
    } else {
      builder.add("-classpath", "''");
    }

    return builder.build();
  }

  /**
   * @return The classpath entries used to invoke javac.
   */
  protected ImmutableSet<Path> getClasspathEntries() {
    if (buildDependencies == BuildDependencies.TRANSITIVE) {
      return transitiveClasspathEntries;
    } else {
      return declaredClasspathEntries;
    }
  }

  @VisibleForTesting
  Set<Path> getSrcs() {
    return javaSourceFilePaths;
  }

  public String getOutputDirectory() {
    return outputDirectory.toString();
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
