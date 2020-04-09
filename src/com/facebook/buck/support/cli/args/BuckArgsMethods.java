/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.cli.args;

import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
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

  private static Iterable<String> getArgsFromTextFile(AbsPath argsPath) throws IOException {
    return Files.readAllLines(argsPath.getPath(), StandardCharsets.UTF_8).stream()
        .filter(line -> !line.isEmpty())
        .collect(ImmutableList.toImmutableList());
  }

  private static Iterable<String> getArgsFromPythonFile(
      String pythonInterpreter, AbsPath argsPath, String suffix) throws IOException {
    Process proc =
        Runtime.getRuntime()
            .exec(new String[] {pythonInterpreter, argsPath.toString(), "--flavors", suffix});
    try (InputStream input = proc.getInputStream();
        OutputStream output = proc.getOutputStream();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      return reader.lines().filter(line -> !line.isEmpty()).collect(Collectors.toList());
    }
  }

  private static Iterable<String> getArgsFromPath(
      String pythonInterpreter, AbsPath argsPath, Optional<String> flavors) throws IOException {
    if (!argsPath.toString().endsWith(".py")) {
      if (flavors.isPresent()) {
        throw new HumanReadableException(
            "Flavors can only be used with python scripts that will output a config. If the file you "
                + "specified is a python script, please make sure the filename ends with .py.");
      }
      return getArgsFromTextFile(argsPath);
    }
    return getArgsFromPythonFile(pythonInterpreter, argsPath, flavors.orElse(""));
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
   * @param pythonInterpreter the python interpreter to use to execute python @ files
   * @param args original args array
   * @param cellMapping a map from cell names to their roots
   * @return args array with AT-files expanded.
   */
  public static ImmutableList<String> expandAtFiles(
      String pythonInterpreter,
      Iterable<String> args,
      ImmutableMap<CellName, AbsPath> cellMapping) {
    // LinkedHashSet is used to preserve insertion order, so that a path can be printed
    Set<String> expansionPath = new LinkedHashSet<>();
    return expandFlagFilesRecursively(pythonInterpreter, args, cellMapping, expansionPath);
  }

  /**
   * Recursively expands flag files into a flat list of command line arguments.
   *
   * <p>Loops are not allowed and result in runtime exception.
   */
  private static ImmutableList<String> expandFlagFilesRecursively(
      String pythonInterpreter,
      Iterable<String> args,
      ImmutableMap<CellName, AbsPath> cellMapping,
      Set<String> expansionPath) {
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
        argumentsBuilder.addAll(
            expandFlagFile(pythonInterpreter, nextFlagFile, cellMapping, expansionPath));
      } else if (arg.startsWith("@")) {
        argumentsBuilder.addAll(
            expandFlagFile(pythonInterpreter, arg.substring(1), cellMapping, expansionPath));
      } else {
        argumentsBuilder.add(arg);
      }
    }
    return argumentsBuilder.build();
  }

  /** Recursively expands flag files into a list of command line flags. */
  private static ImmutableList<String> expandFlagFile(
      String pythonInterpreter,
      String nextFlagFile,
      ImmutableMap<CellName, AbsPath> cellMapping,
      Set<String> expansionPath) {
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
            pythonInterpreter,
            expandFile(pythonInterpreter, nextFlagFile, cellMapping),
            cellMapping,
            expansionPath);
    expansionPath.remove(nextFlagFile);
    return expandedArgs;
  }

  /** Extracts command line options from a file identified by {@code arg} with AT-file syntax. */
  private static Iterable<String> expandFile(
      String pythonInterpreter, String arg, ImmutableMap<CellName, AbsPath> cellMapping) {
    BuckCellArg argfile = BuckCellArg.of(arg);
    String[] parts = argfile.getArg().split("#", 2);
    String unresolvedArgsPath = parts[0];
    AbsPath projectRoot;

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
    AbsPath argsPath = projectRoot.resolve(Paths.get(unresolvedArgsPath));

    if (!Files.exists(argsPath.getPath())) {
      throw new HumanReadableException(
          "The file "
              + unresolvedArgsPath
              + " can't be found. Please make sure the path exists relative to the "
              + "project root.");
    }
    Optional<String> flavors = parts.length == 2 ? Optional.of(parts[1]) : Optional.empty();
    try {
      return getArgsFromPath(pythonInterpreter, argsPath, flavors);
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

  /** Get the location of the python binary that the python wrapper was invoked with */
  public static String getPythonInterpreter(ImmutableMap<String, String> clientEnvironment) {
    return Objects.requireNonNull(
        clientEnvironment.get("BUCK_WRAPPER_PYTHON_BIN"), "BUCK_WRAPPER_PYTHON_BIN was not set!");
  }

  @VisibleForTesting
  protected static void addArgsFromEnv(
      List<String> args, ImmutableMap<String, String> clientEnvironment) {
    // Implicitly add command line arguments based on environmental variables. This is a bad
    // practice and should be considered for infrastructure / debugging purposes only
    if (clientEnvironment.getOrDefault("BUCK_NO_CACHE", "0").equals("1")
        && !args.contains("--no-cache")) {
      args.add("--no-cache");
    }

    if (clientEnvironment.getOrDefault("BUCK_CACHE_READONLY", "0").equals("1")) {
      args.add("-c");
      args.add("cache.http_mode=readonly");
    }
  }

  @VisibleForTesting
  protected static void handleIsolationArgs(List<String> args) {
    int isolationPrefixIndex = args.indexOf("--isolation_prefix");

    if (isolationPrefixIndex == -1) {
      return;
    }

    if (isolationPrefixIndex + 1 < args.size()) {
      // Allow for the argument to --isolation_prefix
      args.remove(isolationPrefixIndex + 1);
      args.remove(isolationPrefixIndex);

      args.add("--config");
      args.add(
          String.format("buck.base_buck_out_dir=%s", System.getProperty("buck.base_buck_out_dir")));
    }
  }

  /**
   * allow --help right after "buck" then followed by subcommand, in order for java code to process
   * this as help, move --help to after subcommand and replace `-h` with `--help`.
   */
  @VisibleForTesting
  protected static void adjustHelpArgs(List<String> args) {
    if (args.indexOf("--help") == 0) {
      args.remove(0);
      args.add("--help");
    }

    int indexOfH = args.indexOf("-h");
    if (indexOfH != -1) {
      args.remove(indexOfH);
      args.add("--help");
    }
  }

  public static ImmutableList<String> expandArgs(
      String pythonInterpreter,
      ImmutableList<String> argsv,
      ImmutableMap<CellName, AbsPath> rootCellMapping,
      ImmutableMap<String, String> clientEnvironment) {
    ImmutableList<String> fileExpandedArgs =
        expandAtFiles(pythonInterpreter, argsv, rootCellMapping);
    int passThroughPosition = fileExpandedArgs.indexOf(PASS_THROUGH_DELIMITER);

    List<String> args;
    List<String> passThroughArgs;

    if (passThroughPosition == -1) {
      args = new ArrayList<>(fileExpandedArgs);
      passThroughArgs = new ArrayList<>();
    } else {
      args = new ArrayList<>(fileExpandedArgs.subList(0, passThroughPosition));
      passThroughArgs =
          new ArrayList<>(fileExpandedArgs.subList(passThroughPosition, fileExpandedArgs.size()));
    }

    addArgsFromEnv(args, clientEnvironment);
    adjustHelpArgs(args);
    handleIsolationArgs(args);

    return ImmutableList.<String>builderWithExpectedSize(args.size() + passThroughArgs.size())
        .addAll(args)
        .addAll(passThroughArgs)
        .build();
  }
}
