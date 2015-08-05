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

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.io.IOException;
import java.util.Set;

public abstract class CommandHelper {

  public static void printJSON(
      CommandRunnerParams params,
      Multimap<BuildTarget, BuildTarget> targetsAndResults)
      throws IOException {
    Multimap<BuildTarget, String> targetsAndResultsNames =
        Multimaps.transformValues(
            targetsAndResults,
            new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget input) {
                return Preconditions.checkNotNull(input.getFullyQualifiedName());
              }
            });
    params.getObjectMapper().writeValue(
        params.getConsole().getStdOut(),
        targetsAndResultsNames.asMap());
  }

  public static void printJSON(
      CommandRunnerParams params,
      Set<BuildTarget> targets) throws IOException {
    Set<String> targetsNames = ImmutableSet.copyOf(
        Collections2.transform(
            targets,
            new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget input) {
                return Preconditions.checkNotNull(input.getFullyQualifiedName());
              }
            }));
    params.getObjectMapper().writeValue(
        params.getConsole().getStdOut(),
        targetsNames);
  }

  public static void printToConsole(
      CommandRunnerParams params,
      Multimap<BuildTarget, BuildTarget> targetsAndDependencies) {
    for (BuildTarget target : ImmutableSortedSet.copyOf(targetsAndDependencies.values())) {
      params.getConsole().getStdOut().println(target.getFullyQualifiedName());
    }
  }

  public static void printToConsole(
      CommandRunnerParams params,
      Set<BuildTarget> targets) {
    for (BuildTarget target : targets) {
      params.getConsole().getStdOut().println(target.getFullyQualifiedName());
    }
  }
}
