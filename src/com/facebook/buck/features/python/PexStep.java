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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class PexStep extends ShellStep {

  // The PEX builder environment variables.
  private final ImmutableMap<String, String> environment;

  // The PEX builder command prefix.
  private final ImmutableList<String> commandPrefix;

  // The path to the executable/directory to create.
  private final Path destination;

  // The main module that begins execution in the PEX.
  private final String entry;

  private final PythonResolvedPackageComponents components;

  private final PythonVersion pythonVersion;
  private final Path pythonPath;

  // The list of native libraries to preload into the interpreter.
  private final ImmutableSet<String> preloadLibraries;

  public PexStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      ImmutableList<String> commandPrefix,
      Path pythonPath,
      PythonVersion pythonVersion,
      Path destination,
      String entry,
      PythonResolvedPackageComponents components,
      ImmutableSet<String> preloadLibraries) {
    super(filesystem.getRootPath());

    this.environment = environment;
    this.commandPrefix = commandPrefix;
    this.pythonPath = pythonPath;
    this.pythonVersion = pythonVersion;
    this.destination = destination;
    this.entry = entry;
    this.components = components;
    this.preloadLibraries = preloadLibraries;
  }

  @Override
  public String getShortName() {
    return "pex";
  }

  /**
   * Return the manifest as a JSON blob to write to the pex processes stdin.
   *
   * <p>We use stdin rather than passing as an argument to the processes since manifest files can
   * occasionally get extremely large, and surpass exec/shell limits on arguments.
   */
  @Override
  protected Optional<String> getStdin(ExecutionContext context) throws IOException {
    // Convert the map of paths to a map of strings before converting to JSON.
    ImmutableMap.Builder<String, String> modulesBuilder = ImmutableMap.builder();
    components.forEachModule((dest, src) -> modulesBuilder.put(dest.toString(), src.toString()));
    ImmutableMap.Builder<String, String> resourcesBuilder = ImmutableMap.builder();
    components.forEachResource(
        (dest, src) -> resourcesBuilder.put(dest.toString(), src.toString()));
    ImmutableMap.Builder<String, String> nativeLibrariesBuilder = ImmutableMap.builder();
    components.forEachNativeLibrary(
        (dest, src) -> nativeLibrariesBuilder.put(dest.toString(), src.toString()));
    return Optional.of(
        ObjectMappers.WRITER.writeValueAsString(
            ImmutableMap.of(
                "modules", modulesBuilder.build(),
                "resources", resourcesBuilder.build(),
                "nativeLibraries", nativeLibrariesBuilder.build(),
                // prebuiltLibraries key kept for compatibility
                "prebuiltLibraries", ImmutableList.<String>of())));
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(commandPrefix);
    builder.add("--python");
    builder.add(pythonPath.toString());
    builder.add("--python-version");
    builder.add(pythonVersion.toString());
    builder.add("--entry-point");
    builder.add(entry);

    if (!components.isZipSafe().orElse(true)) {
      builder.add("--no-zip-safe");
    }

    for (String lib : preloadLibraries) {
      builder.add("--preload", lib);
    }

    builder.add(destination.toString());
    return builder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  @VisibleForTesting
  protected ImmutableList<String> getCommandPrefix() {
    return commandPrefix;
  }
}
