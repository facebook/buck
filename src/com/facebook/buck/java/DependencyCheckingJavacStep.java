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
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.CapturingPrintStream;
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

import java.util.Set;
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
public class DependencyCheckingJavacStep extends JavacInMemoryStep {

  private final ImmutableSet<String> declaredClasspathEntries;

  private final Optional<String> invokingRule;

  private final BuildDependencies buildDependencies;

  private final Optional<SuggestBuildRules> suggestBuildRules;

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

  public static interface SuggestBuildRules extends
      Function<ImmutableSet<String>,ImmutableSet<String>> {}

  public DependencyCheckingJavacStep(
      String outputDirectory,
      Set<String> javaSourceFilePaths,
      Set<String> transitiveClasspathEntries,
      Set<String> declaredClasspathEntries,
      JavacOptions javacOptions,
      Optional<String> pathToOutputAbiFile,
      Optional<String> invokingRule,
      BuildDependencies buildDependencies,
      Optional<SuggestBuildRules> suggestBuildRules) {
    super(outputDirectory,
        javaSourceFilePaths,
        transitiveClasspathEntries,
        javacOptions,
        pathToOutputAbiFile);

    this.declaredClasspathEntries = ImmutableSet.copyOf(declaredClasspathEntries);
    this.invokingRule = Preconditions.checkNotNull(invokingRule);
    this.buildDependencies = Preconditions.checkNotNull(buildDependencies);
    this.suggestBuildRules = Preconditions.checkNotNull(suggestBuildRules);
  }

  @Override
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
      int transitiveResult = buildWithClasspath(context, getClasspathEntries());
      if (transitiveResult == 0) {
        ImmutableSet<String> failedImports = findFailedImports(firstOrderStderr);

        context.getStdErr().println(String.format("Rule %s builds with its transitive " +
            "dependencies but not with its first order dependencies.", invokingRule.or("")));
        context.getStdErr().println("The following packages were missing:");
        context.getStdErr().println(Joiner.on(LINE_SEPARATOR).join(failedImports));
        if (suggestBuildRules.isPresent()) {
          context.getStdErr().println("Try adding the following deps:");
          context.getStdErr().println(Joiner.on(LINE_SEPARATOR)
              .join(suggestBuildRules.get().apply(failedImports)));
        }
        context.getStdErr().println();
        context.getStdErr().println();
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

  @Override
  protected ImmutableSet<String> getClasspathEntries() {
    if (buildDependencies == BuildDependencies.TRANSITIVE) {
      return super.getClasspathEntries();
    } else {
      return declaredClasspathEntries;
    }
  }
}
