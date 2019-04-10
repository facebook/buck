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

package com.facebook.buck.cli;

import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Set;

/** Utility class with print methods */
public final class CommandHelper {

  private CommandHelper() {}

  /**
   * Prints target and result map's json representation into printStream.
   *
   * @param targetsAndResults input to query result multi map
   * @param printStream print stream for output
   * @throws IOException in case of IO exception during json writing operation
   */
  public static void printJsonOutput(
      Multimap<String, QueryTarget> targetsAndResults, PrintStream printStream) throws IOException {
    Multimap<String, String> targetsAndResultsNames =
        Multimaps.transformValues(
            targetsAndResults, input -> stringify(Objects.requireNonNull(input)));
    ObjectMappers.WRITER.writeValue(printStream, targetsAndResultsNames.asMap());
  }

  /**
   * Prints targets set json representation into printStream.
   *
   * @param targets set of query result
   * @param printStream print stream for output
   * @throws IOException in case of IO exception during json writing operation
   */
  public static void printJsonOutput(Set<QueryTarget> targets, PrintStream printStream)
      throws IOException {
    Set<String> targetsNames =
        targets.stream()
            .peek(Objects::requireNonNull)
            .map(CommandHelper::stringify)
            .collect(ImmutableSet.toImmutableSet());

    ObjectMappers.WRITER.writeValue(printStream, targetsNames);
  }

  /**
   * Prints target and dependencies map into printStream.
   *
   * @param targetsAndDependencies input to query result multi map
   * @param printStream print stream for output
   */
  public static void print(
      Multimap<String, QueryTarget> targetsAndDependencies, PrintStream printStream) {
    ImmutableSortedSet.copyOf(targetsAndDependencies.values()).stream()
        .map(CommandHelper::stringify)
        .forEach(printStream::println);
  }

  /**
   * Prints target set into printStream.
   *
   * @param targets set of query result
   * @param printStream print stream for output
   */
  public static void print(Set<QueryTarget> targets, PrintStream printStream) {
    targets.stream().map(CommandHelper::stringify).forEach(printStream::println);
  }

  /**
   * Prints short description of a given command into printStream.
   *
   * @param command CLI command
   * @param printStream print stream for output
   */
  public static void printShortDescription(Command command, PrintStream printStream) {
    printStream.println("Description: ");
    printStream.println("  " + command.getShortDescription());
    printStream.println();
  }

  private static String stringify(QueryTarget target) {
    if (target instanceof QueryFileTarget) {
      QueryFileTarget fileTarget = (QueryFileTarget) target;
      SourcePath path = fileTarget.getPath();
      if (path instanceof PathSourcePath) {
        PathSourcePath psp = (PathSourcePath) path;
        return psp.getRelativePath().toString();
      }
    }

    return target.toString();
  }
}
