/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavacErrorParser {

  private final ProjectFilesystem filesystem;
  private final JavaPackageFinder javaPackageFinder;

  private static ImmutableList<Pattern> onePartPatterns =
      ImmutableList.of(
          Pattern.compile("error: cannot access (?<symbol>\\S+)"),
          Pattern.compile(
              "error: package \\S+ does not exist"
                  + System.lineSeparator()
                  + "import (?<symbol>\\S+);"),
          Pattern.compile(
              "error: package \\S+ does not exist"
                  + System.lineSeparator()
                  + "import static (?<symbol>\\S+)\\.[^.]+;"));

  private static ImmutableList<Pattern> twoPartPatterns =
      ImmutableList.of(
          Pattern.compile(
              "\\s*symbol:\\s+class (?<class>\\S+)"
                  + System.lineSeparator()
                  + "\\s*location:\\s+package (?<package>\\S+)"));

  // These patterns match missing symbols that live in the current package.  Usually, that means one
  // java package that's split up into multiple java_library rules, which depend on each other.
  // Classes in the same package can reference symbols without an import or fully qualified name,
  // which means we have to infer the package name from the filepath in the error.
  // NOTE: Regular missing symbols in other packages will often generate compiler errors that match
  // these patterns, because imported symbols are used unqualified after the import. That means we
  // might go looking for an imported symbol in the current package (wrong) in addition to the
  // package it was imported from (right). That's ultimately fine, because the symbol can't exist in
  // both places (without causing another compiler error).
  private static ImmutableList<Pattern> localPackagePatterns =
      ImmutableList.of(
          Pattern.compile(
              MoreStrings.linesToText(
                  "^(?<file>.+):[0-9]+: error: cannot find symbol",
                  ".*",
                  ".*",
                  "\\s*symbol:\\s+(class|variable) (?<class>\\S+)")),
          Pattern.compile("^(?<file>.+):[0-9]+: error: package (?<class>\\S+) does not exist"));

  public JavacErrorParser(ProjectFilesystem filesystem, JavaPackageFinder javaPackageFinder) {
    this.filesystem = filesystem;
    this.javaPackageFinder = javaPackageFinder;
  }

  public Optional<String> getMissingSymbolFromCompilerError(String error) {
    for (Pattern pattern : onePartPatterns) {
      Matcher matcher = pattern.matcher(error);
      if (matcher.find()) {
        return Optional.of(matcher.group("symbol"));
      }
    }

    for (Pattern pattern : twoPartPatterns) {
      Matcher matcher = pattern.matcher(error);
      if (matcher.find()) {
        return Optional.of(matcher.group("package") + "." + matcher.group("class"));
      }
    }

    for (Pattern pattern : localPackagePatterns) {
      Matcher matcher = pattern.matcher(error);
      if (matcher.find()) {
        return getMissingSymbolInLocalPackage(matcher);
      }
    }

    return Optional.empty();
  }

  private Optional<String> getMissingSymbolInLocalPackage(Matcher matcher) {
    String fileName = matcher.group("file");
    Path repoRoot = filesystem.getRootPath().toAbsolutePath().normalize();
    Path filePath = Paths.get(fileName).toAbsolutePath().normalize();
    try {
      filePath = repoRoot.relativize(filePath);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    String packageName = javaPackageFinder.findJavaPackage(filePath);
    String className = matcher.group("class");
    return Optional.of(packageName + "." + className);
  }
}
