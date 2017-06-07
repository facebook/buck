/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** Utility class for methods related to args handling. */
public class BuckArgsMethods {

  private BuckArgsMethods() {
    // Utility class.
  }

  /**
   * Expland AT-file syntax in a way that matches what args4j does. We have this because we'd like
   * to correctly log the arguments coming from the AT-files and there is no way to get the expanded
   * args array from args4j.
   *
   * @param args original args array
   * @param projectRoot path against which any {@code @args} path arguments will be resolved.
   * @return args array with AT-files expanded.
   */
  public static String[] expandAtFiles(String[] args, Path projectRoot) {
    return Arrays.stream(args)
        .flatMap(
            arg -> {
              if (arg.startsWith("@")) {
                Path argsPath = projectRoot.resolve(Paths.get(arg.substring(1)));
                try {
                  return Files.readAllLines(argsPath, Charsets.UTF_8).stream();
                } catch (IOException e) {
                  throw new HumanReadableException(e, "Could not read options from " + arg);
                }
              } else {
                return ImmutableList.of(arg).stream();
              }
            })
        .toArray(String[]::new);
  }

  /**
   * Drops options from the args array.
   *
   * @param args args array.
   * @param optionsToSkip if args contains an element from this array skip the element and the
   *     element immediately after it.
   * @return filtered args array
   */
  public static ImmutableList<String> filterArgs(
      List<String> args, ImmutableSet<String> optionsToSkip) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    for (int i = 0; i < args.size(); ++i) {
      if (optionsToSkip.contains(args.get(i))) {
        i += 1;
      } else {
        result.add(args.get(i));
      }
    }
    return result.build();
  }
}
