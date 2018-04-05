/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.go;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import org.immutables.value.Value;

/** Factory to create {@link GoPlatform}s from a {@link BuckConfig} section. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractGoPlatformFactory {

  private static final Path DEFAULT_GO_TOOL = Paths.get("go");

  @Value.Parameter
  public abstract BuckConfig getBuckConfig();

  @Value.Parameter
  public abstract ProcessExecutor getProcessExecutor();

  @Value.Parameter
  public abstract ExecutableFinder getExecutableFinder();

  /** @return the {@link GoPlatform} defined in the given {@code section}. */
  public GoPlatform getPlatform(String os, String arch, String section) {
    Path goRoot = getGoRoot(section);
    Path toolsDir = getToolDir(section);
    return GoPlatform.builder()
        .setGoOs(os)
        .setGoArch(arch)
        .setGoRoot(goRoot)
        .setToolDir(toolsDir)
        .setCompiler(getGoTool(section, goRoot, toolsDir, "compiler", "compile", "compiler_flags"))
        .setAssembler(getGoTool(section, goRoot, toolsDir, "assembler", "asm", "asm_flags"))
        .setAssemblerIncludeDirs(ImmutableList.of(goRoot.resolve("pkg").resolve("include")))
        .setCGo(getGoTool(section, goRoot, toolsDir, "cgo", "cgo", ""))
        .setPacker(getGoTool(section, goRoot, toolsDir, "packer", "pack", ""))
        .setLinker(getGoTool(section, goRoot, toolsDir, "linker", "link", "linker_flags"))
        .setCover(getGoTool(section, goRoot, toolsDir, "cover", "cover", ""))
        .build();
  }

  private Tool getGoTool(
      String section,
      Path goRoot,
      Path toolsDir,
      String configName,
      String toolName,
      String extraFlagsConfigKey) {
    Path toolPath = getBuckConfig().getPath(section, configName).orElse(toolsDir.resolve(toolName));

    CommandTool.Builder builder =
        new CommandTool.Builder(
            new HashedFileTool(() -> getBuckConfig().getPathSourcePath(toolPath)));
    if (!extraFlagsConfigKey.isEmpty()) {
      for (String arg : getFlags(section, extraFlagsConfigKey)) {
        builder.addArg(arg);
      }
    }
    builder.addEnv("GOROOT", goRoot.toString());
    return builder.build();
  }

  private ImmutableList<String> getFlags(String section, String key) {
    return ImmutableList.copyOf(
        Splitter.on(" ")
            .omitEmptyStrings()
            .split(getBuckConfig().getValue(section, key).orElse("")));
  }

  private Optional<Path> getConfiguredGoRoot(String section) {
    return getBuckConfig().getPath(section, "root");
  }

  private Path getGoRoot(String section) {
    return getConfiguredGoRoot(section)
        .orElseGet(() -> Paths.get(getGoEnvFromTool(section, "GOROOT")));
  }

  private String getGoEnvFromTool(String section, String env) {
    Path goTool = getGoToolPath(section);
    Optional<ImmutableMap<String, String>> goRootEnv =
        getConfiguredGoRoot(section).map(input -> ImmutableMap.of("GOROOT", input.toString()));
    try {
      ProcessExecutor.Result goToolResult =
          getProcessExecutor()
              .launchAndExecute(
                  ProcessExecutorParams.builder()
                      .addCommand(goTool.toString(), "env", env)
                      .setEnvironment(goRootEnv)
                      .build(),
                  EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
                  /* stdin */ Optional.empty(),
                  /* timeOutMs */ Optional.empty(),
                  /* timeoutHandler */ Optional.empty());
      if (goToolResult.getExitCode() == 0) {
        return CharMatcher.whitespace().trimFrom(goToolResult.getStdout().get());
      } else {
        throw new HumanReadableException(goToolResult.getStderr().get());
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new HumanReadableException(
          e, "Could not run \"%s env %s\": %s", goTool, env, e.getMessage());
    }
  }

  private Path getGoToolPath(String section) {
    Optional<Path> goTool = getBuckConfig().getPath(section, "tool");
    if (goTool.isPresent()) {
      return goTool.get();
    }

    // Try resolving it via the go root config var. We can't use goRootSupplier here since that
    // would create a recursion.
    Optional<Path> goRoot = getConfiguredGoRoot(section);
    if (goRoot.isPresent()) {
      return goRoot.get().resolve("bin").resolve("go");
    }

    return getExecutableFinder().getExecutable(DEFAULT_GO_TOOL, getBuckConfig().getEnvironment());
  }

  private Path getToolDir(String section) {
    return getBuckConfig()
        .getPath(section, "tool_dir")
        .orElseGet(() -> Paths.get(getGoEnvFromTool(section, "GOTOOLDIR")));
  }
}
