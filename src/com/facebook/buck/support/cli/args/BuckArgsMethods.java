/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.support.cli.args;

import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Utility class for methods related to args handling. */
public class BuckArgsMethods {

  private static final ImmutableSet<String> FLAG_FILE_OPTIONS = ImmutableSet.of("--flagfile");
  private static final String PASS_THROUGH_DELIMITER = "--";

  private BuckArgsMethods() {
    // Utility class.
  }

  private static Iterable<String> getArgsFromTextFile(Path argsPath) throws IOException {
    return Files.readAllLines(argsPath, Charsets.UTF_8).stream()
        .filter(line -> !line.isEmpty())
        .collect(ImmutableList.toImmutableList());
  }

  private static Iterable<String> getArgsFromPythonFile(Path argsPath, String suffix)
      throws IOException {
    Process proc =
        Runtime.getRuntime()
            .exec(
                new String[] {"python", argsPath.toAbsolutePath().toString(), "--flavors", suffix});
    try (InputStream input = proc.getInputStream();
        OutputStream output = proc.getOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, Charsets.UTF_8))) {
      return reader.lines().filter(line -> !line.isEmpty()).collect(Collectors.toList());
    }
  }

  private static Iterable<String> getArgsFromPath(Path argsPath, Optional<String> flavors)
      throws IOException {
    if (!argsPath.toAbsolutePath().toString().endsWith(".py")) {
      if (flavors.isPresent()) {
        throw new HumanReadableException(
            "Flavors can only be used with python scripts that will output a config. If the file you "
                + "specified is a python script, please make sure the filename ends with .py.");
      }
      return getArgsFromTextFile(argsPath);
    }
    return getArgsFromPythonFile(argsPath, flavors.orElse(""));
  }

  /**
   * Expand AT-file syntax in a way that matches what args4j does. We have this because we'd like to
   * correctly log the arguments coming from the AT-files and there is no way to get the expanded
   * args array from args4j.
   *
   * <p>In addition to files passed using a regular {@code @} syntax, this method also extracts
   * command line arguments from AT-file syntax files passed via {@code --flagfile} command line
   * option.
   *
   * @param args original args array
   * @param cellMapping a map from cell names to their roots
   * @return args array with AT-files expanded.
   */
  public static ImmutableList<String> expandAtFiles(
      Iterable<String> args, ImmutableMap<CellName, Path> cellMapping) {
    // LinkedHashSet is used to preserve insertion order, so that a path can be printed
    Set<String> expansionPath = new LinkedHashSet<>();
    return expandFlagFilesRecursively(args, cellMapping, expansionPath);
  }

  /**
   * Recursively expands flag files into a flat list of command line arguments.
   *
   * <p>Loops are not allowed and result in runtime exception.
   */
  private static ImmutableList<String> expandFlagFilesRecursively(
      Iterable<String> args, ImmutableMap<CellName, Path> cellMapping, Set<String> expansionPath) {
    Iterator<String> argsIterator = args.iterator();
    ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
    while (argsIterator.hasNext()) {
      String arg = argsIterator.next();
      if (PASS_THROUGH_DELIMITER.equals(arg)) {
        // all flags after -- should be passed through without any preprocessing
        argumentsBuilder.add(arg);
        argumentsBuilder.addAll(argsIterator);
        break;
      }
      if (FLAG_FILE_OPTIONS.contains(arg)) {
        if (!argsIterator.hasNext()) {
          throw new HumanReadableException(arg + " should be followed by a path.");
        }
        String nextFlagFile = argsIterator.next();
        argumentsBuilder.addAll(expandFlagFile(nextFlagFile, cellMapping, expansionPath));
      } else if (arg.startsWith("@")) {
        argumentsBuilder.addAll(expandFlagFile(arg.substring(1), cellMapping, expansionPath));
      } else {
        argumentsBuilder.add(arg);
      }
    }
    return argumentsBuilder.build();
  }

  /** Recursively expands flag files into a list of command line flags. */
  private static ImmutableList<String> expandFlagFile(
      String nextFlagFile, ImmutableMap<CellName, Path> cellMapping, Set<String> expansionPath) {
    if (expansionPath.contains(nextFlagFile)) {
      // expansion path is a linked hash set, so it preserves order
      throw new HumanReadableException(
          "Expansion loop detected: "
              + String.join(" -> ", expansionPath)
              + "."
              + System.lineSeparator()
              + "Please make sure your flag files form a directed acyclic graph.");
    }
    expansionPath.add(nextFlagFile);
    ImmutableList<String> expandedArgs =
        expandFlagFilesRecursively(
            expandFile(nextFlagFile, cellMapping), cellMapping, expansionPath);
    expansionPath.remove(nextFlagFile);
    return expandedArgs;
  }

  /** Extracts command line options from a file identified by {@code arg} with AT-file syntax. */
  private static Iterable<String> expandFile(String arg, ImmutableMap<CellName, Path> cellMapping) {
    BuckCellArg argfile = BuckCellArg.of(arg);
    String[] parts = argfile.getArg().split("#", 2);
    String unresolvedArgsPath = parts[0];
    Path projectRoot = null;

    // Try to resolve the name to a path if present
    if (argfile.getCellName().isPresent()) {
      projectRoot = cellMapping.get(CellName.of(argfile.getCellName().get()));
      if (projectRoot == null) {
        String cellName = argfile.getCellName().get();
        throw new HumanReadableException(
            String.format(
                "The cell '%s' was not found. Did you mean '%s/%s'?",
                cellName, cellName, unresolvedArgsPath));
      }
    } else {
      projectRoot = cellMapping.get(CellName.ROOT_CELL_NAME);
    }
    Objects.requireNonNull(projectRoot, "Project root not resolved");
    Path argsPath = projectRoot.resolve(Paths.get(unresolvedArgsPath));

    if (!Files.exists(argsPath)) {
      throw new HumanReadableException(
          "The file "
              + unresolvedArgsPath
              + " can't be found. Please make sure the path exists relative to the "
              + "project root.");
    }
    Optional<String> flavors = parts.length == 2 ? Optional.of(parts[1]) : Optional.empty();
    try {
      return getArgsFromPath(argsPath, flavors);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Could not read options from " + arg);
    }
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
