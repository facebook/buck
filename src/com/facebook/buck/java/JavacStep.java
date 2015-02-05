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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public class JavacStep implements Step {

  private final Path outputDirectory;

  private final Optional<Path> workingDirectory;

  private final ImmutableSet<Path> javaSourceFilePaths;

  private final Optional<Path> pathToSrcsList;

  private final JavacOptions javacOptions;

  private final ImmutableSet<Path> transitiveClasspathEntries;

  private final ImmutableSet<Path> declaredClasspathEntries;

  private final BuildTarget invokingRule;

  private final BuildDependencies buildDependencies;

  private final Optional<SuggestBuildRules> suggestBuildRules;

  /**
   * Will be {@code true} once {@link Javac#buildWithClasspath(ExecutionContext, BuildTarget,
   * ImmutableList, ImmutableSet, Optional, Optional)} has been invoked.
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
      ImmutableList.of(
          IMPORT_FAILURE,
          PACKAGE_FAILURE,
          ACCESS_FAILURE,
          CLASS_NOT_FOUND,
          CLASS_SYMBOL_NOT_FOUND);

  private static final String LINE_SEPARATOR = System.getProperty("line.separator");

  public static interface SuggestBuildRules {
    public ImmutableSet<String> suggest(ProjectFilesystem filesystem,
        ImmutableSet<String> failedImports);
  }

  public JavacStep(
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Set<Path> javaSourceFilePaths,
      Optional<Path> pathToSrcsList,
      Set<Path> transitiveClasspathEntries,
      Set<Path> declaredClasspathEntries,
      JavacOptions javacOptions,
      BuildTarget invokingRule,
      BuildDependencies buildDependencies,
      Optional<SuggestBuildRules> suggestBuildRules) {
    this.outputDirectory = outputDirectory;
    this.workingDirectory = workingDirectory;
    this.javaSourceFilePaths = ImmutableSet.copyOf(javaSourceFilePaths);
    this.pathToSrcsList = pathToSrcsList;
    this.transitiveClasspathEntries = ImmutableSet.copyOf(transitiveClasspathEntries);
    this.javacOptions = javacOptions;

    this.declaredClasspathEntries = ImmutableSet.copyOf(declaredClasspathEntries);
    this.invokingRule = invokingRule;
    this.buildDependencies = buildDependencies;
    this.suggestBuildRules = suggestBuildRules;
  }

  @Override
  public final int execute(ExecutionContext context) throws IOException, InterruptedException {
    try {
      return executeBuild(context);
    } finally {
      isExecuted.set(true);
    }
  }

  public int executeBuild(ExecutionContext context) throws IOException, InterruptedException {
    // Build up the compilation task.
    if (buildDependencies == BuildDependencies.FIRST_ORDER_ONLY) {
      return getJavac().buildWithClasspath(
          context,
          invokingRule,
          getOptions(context, declaredClasspathEntries),
          javaSourceFilePaths,
          pathToSrcsList,
          workingDirectory);
    } else if (buildDependencies == BuildDependencies.WARN_ON_TRANSITIVE) {
      return tryBuildWithFirstOrderDeps(context);
    } else {
      return getJavac().buildWithClasspath(
          context,
          invokingRule,
          getOptions(context, transitiveClasspathEntries),
          javaSourceFilePaths,
          pathToSrcsList,
          workingDirectory);
    }
  }

  private int tryBuildWithFirstOrderDeps(ExecutionContext context)
      throws InterruptedException, IOException {
    CapturingPrintStream stdout = new CapturingPrintStream();
    CapturingPrintStream stderr = new CapturingPrintStream();
    try (ExecutionContext firstOrderContext = context.createSubContext(stdout, stderr)) {

      Javac javac = getJavac();

      int declaredDepsResult = javac.buildWithClasspath(
          firstOrderContext,
          invokingRule,
          getOptions(context, declaredClasspathEntries),
          javaSourceFilePaths,
          pathToSrcsList,
          workingDirectory);

      String firstOrderStdout = stdout.getContentsAsString(Charsets.UTF_8);
      String firstOrderStderr = stderr.getContentsAsString(Charsets.UTF_8);

      if (declaredDepsResult != 0) {
        int transitiveResult = javac.buildWithClasspath(
            context,
            invokingRule,
            getOptions(context, transitiveClasspathEntries),
            javaSourceFilePaths,
            pathToSrcsList,
            workingDirectory);
        if (transitiveResult == 0) {
          ImmutableSet<String> failedImports = findFailedImports(firstOrderStderr);
          ImmutableList.Builder<String> errorMessage = ImmutableList.builder();

          String invoker = invokingRule.toString();
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
  }

  @VisibleForTesting
  Javac getJavac() {
    return javacOptions.getJavac();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getJavac().getDescription(
        context,
        getOptions(context, getClasspathEntries()),
        javaSourceFilePaths,
        pathToSrcsList);
  }

  @Override
  public String getShortName() {
    return getJavac().getShortName();
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
   * Returns a list of command-line options to pass to javac.  These options reflect
   * the configuration of this javac command.
   *
   * @param context the ExecutionContext with in which javac will run
   * @return list of String command-line options.
   */
  @VisibleForTesting
  ImmutableList<String> getOptions(
      ExecutionContext context,
      Set<Path> buildClasspathEntries) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    ProjectFilesystem filesystem = context.getProjectFilesystem();
    javacOptions.appendOptionsToList(builder, context.getProjectFilesystem().getAbsolutifier());

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
  @VisibleForTesting
  ImmutableSet<Path> getClasspathEntries() {
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
}
