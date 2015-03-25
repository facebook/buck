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

package com.facebook.buck.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavacErrorParser {

  private final ProjectFilesystem filesystem;
  private final JavaPackageFinder javaPackageFinder;

  private static ImmutableList<Pattern> onePartPatterns = ImmutableList.of(
      Pattern.compile(
          "error: cannot access (?<symbol>\\S+)"),
      Pattern.compile(
          "error: package \\S+ does not exist\nimport (?<symbol>\\S+);"),
      Pattern.compile(
          "error: package \\S+ does not exist\nimport static (?<symbol>\\S+)\\.[^.]+;"));

  private static ImmutableList<Pattern> twoPartPatterns = ImmutableList.of(
      Pattern.compile(
          "\\s*symbol:\\s+class (?<class>\\S+)\n\\s*location:\\s+package (?<package>\\S+)"));

  // These patterns match missing symbols that live in the current package.  Usually, that means one
  // java package that's split up into multiple java_library rules, which depend on each other.
  // Classes in the same package can reference symbols without an import or fully qualified name,
  // which means we have to infer the package name from the filepath in the error.
  // NOTE: Regular missing symbols in other packages will often generate compiler errors that match
  // these patterns, because imported symbols are used unqualified after the import. That means we
  // might go looking for an imported symbol in the current package (wrong) in addition to the
  // package it was imported from (right). That's ultimately fine, because the symbol can't exist in
  // both places (without causing another compiler error).
  private static ImmutableList<Pattern> localPackagePatterns = ImmutableList.of(
      Pattern.compile(
          "^(?<file>.+):[0-9]+: error: cannot find symbol\n" +
          ".*\n" +
          ".*\n" +
          "\\s*symbol:\\s+(class|variable) (?<class>\\S+)"),
      Pattern.compile(
          "^(?<file>.+):[0-9]+: error: package (?<class>\\S+) does not exist"));

  public JavacErrorParser(ProjectFilesystem filesystem, JavaPackageFinder javaPackageFinder) {
    this.filesystem = filesystem;
    this.javaPackageFinder = javaPackageFinder;
  }

  public Optional<String> getMissingSymbolFromCompilerError(String error) {
    for (Pattern pattern: onePartPatterns) {
      Matcher matcher = pattern.matcher(error);
      if (matcher.find()) {
        return Optional.of(matcher.group("symbol"));
      }
    }

    for (Pattern pattern: twoPartPatterns) {
      Matcher matcher = pattern.matcher(error);
      if (matcher.find()) {
        return Optional.of(matcher.group("package") + "." + matcher.group("class"));
      }
    }

    for (Pattern pattern: localPackagePatterns) {
      Matcher matcher = pattern.matcher(error);
      if (matcher.find()) {
        return getMissingSymbolInLocalPackage(matcher);
      }
    }

    return Optional.absent();
  }

  private Optional<String> getMissingSymbolInLocalPackage(Matcher matcher) {
    String fileName = matcher.group("file");
    String className = matcher.group("class");
    Path repoRoot = filesystem.getRootPath().toAbsolutePath().normalize();
    Path relativePath = repoRoot.relativize(Paths.get(fileName));
    String packageName = javaPackageFinder.findJavaPackage(relativePath);
    return Optional.of(packageName + "." + className);
  }
}
