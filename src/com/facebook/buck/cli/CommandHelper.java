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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Set;

public abstract class CommandHelper {

  public static void printJSON(
      CommandRunnerParams params, Multimap<String, QueryTarget> targetsAndResults)
      throws IOException {
    Multimap<String, String> targetsAndResultsNames =
        Multimaps.transformValues(
            targetsAndResults, input -> stringify(Preconditions.checkNotNull(input)));
    ObjectMappers.WRITER.writeValue(
        params.getConsole().getStdOut(), targetsAndResultsNames.asMap());
  }

  public static void printJSON(CommandRunnerParams params, Set<QueryTarget> targets)
      throws IOException {
    Set<String> targetsNames =
        targets
            .stream()
            .peek(Objects::requireNonNull)
            .map(CommandHelper::stringify)
            .collect(ImmutableSet.toImmutableSet());

    ObjectMappers.WRITER.writeValue(params.getConsole().getStdOut(), targetsNames);
  }

  public static void printToConsole(
      CommandRunnerParams params, Multimap<String, QueryTarget> targetsAndDependencies) {
    for (QueryTarget target : ImmutableSortedSet.copyOf(targetsAndDependencies.values())) {
      params.getConsole().getStdOut().println(stringify(target));
    }
  }

  public static void printToConsole(CommandRunnerParams params, Set<QueryTarget> targets) {
    for (QueryTarget target : targets) {
      params.getConsole().getStdOut().println(stringify(target));
    }
  }

  public static void printShortDescription(Command command, PrintStream stream) {
    stream.println("Description: ");
    stream.println("  " + command.getShortDescription());
    stream.println();
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
