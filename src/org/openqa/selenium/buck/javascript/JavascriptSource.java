/*
 * Copyright 2013-present Facebook, Inc.
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

package org.openqa.selenium.buck.javascript;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavascriptSource {

//  private static final Pattern ADD_DEP = Pattern.compile(
//    Joiner.on("").join(
//        "^goog.addDependency\\s*\\(\\s*",
//        "['\"]([^'\"]+)['\"]",     // Relative path from Closure's base.js
//        "\\s*,\\s*",
//        "\\[([^\\]]+)?\\]",        // Provided symbols
//        "\\s*,\\s*",
//        "\\[([^\\]]+)?\\]",        // Required symbols
//        "\\s*",
//        "(?:,\\s*(true|false))?",  // Module flag.
//        "\\s*\\)"
//    ));

  private static final Pattern MODULE = Pattern.compile(
      "^goog\\.module\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

  // goog.require statement may have a LHS assignment if inside a goog.module
  // file. This is a simplified version of:
  // https://github.com/google/closure-compiler/blob/master/src/com/google/javascript/jscomp/deps/JsFileParser.java#L41
  @SuppressWarnings("checkstyle:linelength")
  private static final Pattern REQUIRE = Pattern.compile(
      "^\\s*(?:(?:var|let|const)\\s+[a-zA-Z_$][a-zA-Z0-9$_]*\\s*=\\s*)?goog\\.require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
  private static final Pattern PROVIDE = Pattern.compile(
      "^goog\\.provide\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");


  private final Path path;
  private final ImmutableSortedSet<String> provides;
  private final ImmutableSortedSet<String> requires;

  public JavascriptSource(Path path) {
    this.path = Preconditions.checkNotNull(path);

    ImmutableSortedSet.Builder<String> toProvide = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<String> toRequire = ImmutableSortedSet.naturalOrder();

    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        Matcher moduleMatcher = MODULE.matcher(line);
        if (moduleMatcher.find()) {
          toProvide.add(moduleMatcher.group(1));
        }

        Matcher requireMatcher = REQUIRE.matcher(line);
        if (requireMatcher.find()) {
          toRequire.add(requireMatcher.group(1));
        }

        Matcher provideMatcher = PROVIDE.matcher(line);
        if (provideMatcher.find()) {
          toProvide.add(provideMatcher.group(1));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    this.provides = toProvide.build();
    this.requires = toRequire.build();
  }

  JavascriptSource(String path, Iterable<String> provides, Iterable<String> requires) {
    this.path = Paths.get(path);
    this.provides = ImmutableSortedSet.copyOf(provides);
    this.requires = ImmutableSortedSet.copyOf(requires);
  }

  public Path getPath() {
    return path;
  }

  public ImmutableSortedSet<String> getProvides() {
    return provides;
  }

  public ImmutableSortedSet<String> getRequires() {
    return requires;
  }

  @Override
  public String toString() {
    return String.format("%s -> provide: %s  + require: %s", path, provides, requires);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    JavascriptSource that = (JavascriptSource) o;

    return path.equals(that.path) &&
        provides.equals(that.provides) &&
        requires.equals(that.requires);
  }

  @Override
  public int hashCode() {
    int result = path.hashCode();
    result = 31 * result + provides.hashCode();
    result = 31 * result + requires.hashCode();
    return result;
  }
}
