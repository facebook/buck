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

package com.facebook.buck.python;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;

public class PexStep extends ShellStep {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Path to the tool to generate the pex file.
  private final Path pathToPex;

  // The path to the executable to create.
  private final Path destination;

  // The main module that begins execution in the PEX.
  private final String entry;

  // The map of modules to sources to package into the PEX.
  private final ImmutableMap<Path, Path> modules;

  // The map of resources to include in the PEX.
  private final ImmutableMap<Path, Path> resources;
  private final Path pythonPath;

  // The map of native libraries to include in the PEX.
  private final ImmutableMap<Path, Path> nativeLibraries;

  public PexStep(
      Path pathToPex,
      Path pythonPath,
      Path destination,
      String entry,
      ImmutableMap<Path, Path> modules,
      ImmutableMap<Path, Path> resources,
      ImmutableMap<Path, Path> nativeLibraries) {
    this.pathToPex = pathToPex;
    this.pythonPath = pythonPath;
    this.destination = destination;
    this.entry = entry;
    this.modules = modules;
    this.resources = resources;
    this.nativeLibraries = nativeLibraries;
  }

  @Override
  public String getShortName() {
    return "pex";
  }

  /** Return the manifest as a JSON blob to write to the pex processes stdin.
   * <p>
   * We use stdin rather than passing as an argument to the processes since
   * manifest files can occasionally get extremely large, and surpass exec/shell
   * limits on arguments.
   */
  @Override
  protected Optional<String> getStdin() {
    // Convert the map of paths to a map of strings before converting to JSON.
    ImmutableMap.Builder<String, String> modulesBuilder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, Path> ent : modules.entrySet()) {
      modulesBuilder.put(ent.getKey().toString(), ent.getValue().toString());
    }
    ImmutableMap.Builder<String, String> resourcesBuilder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, Path> ent : resources.entrySet()) {
      resourcesBuilder.put(ent.getKey().toString(), ent.getValue().toString());
    }
    ImmutableMap.Builder<String, String> nativeLibrariesBuilder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, Path> ent : nativeLibraries.entrySet()) {
      nativeLibrariesBuilder.put(ent.getKey().toString(), ent.getValue().toString());
    }
    try {
      return Optional.of(MAPPER.writeValueAsString(ImmutableMap.of(
          "modules", modulesBuilder.build(),
          "resources", resourcesBuilder.build(),
          "nativeLibraries", nativeLibrariesBuilder.build())));
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.of(
        pathToPex.toString(),
        "--python", pythonPath.toString(),
        "--entry-point", entry,
        destination.toString());
  }

}
