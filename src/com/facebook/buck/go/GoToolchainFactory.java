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

package com.facebook.buck.go;

import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;

public class GoToolchainFactory implements ToolchainFactory<GoToolchain> {

  private static final Path DEFAULT_GO_TOOL = Paths.get("go");

  @Override
  public Optional<GoToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    GoPlatformFlavorDomain platformFlavorDomain = new GoPlatformFlavorDomain();
    GoBuckConfig goBuckConfig = new GoBuckConfig(context.getBuckConfig());

    return Optional.of(
        GoToolchain.of(
            goBuckConfig,
            platformFlavorDomain,
            getDefaultPlatform(goBuckConfig, platformFlavorDomain),
            getGoRoot(goBuckConfig, context.getProcessExecutor(), context.getExecutableFinder()),
            getToolDir(goBuckConfig, context.getProcessExecutor(), context.getExecutableFinder())));
  }

  private GoPlatform getDefaultPlatform(
      GoBuckConfig goBuckConfig, GoPlatformFlavorDomain platformFlavorDomain) {
    Optional<String> configValue = goBuckConfig.getDefaultPlatform();
    Optional<GoPlatform> goPlatform;
    if (configValue.isPresent()) {
      goPlatform = platformFlavorDomain.getValue(InternalFlavor.of(configValue.get()));
      if (!goPlatform.isPresent()) {
        throw new HumanReadableException(
            "Bad go platform value for go.default_platform = %s", configValue);
      }
    } else {
      Platform platform = goBuckConfig.getDelegate().getPlatform();
      Architecture architecture = goBuckConfig.getDelegate().getArchitecture();
      goPlatform = platformFlavorDomain.getValue(platform, architecture);
      if (!goPlatform.isPresent()) {
        throw new HumanReadableException(
            "Couldn't determine default go platform for %s %s", platform, architecture);
      }
    }
    return goPlatform.get();
  }

  private Path getGoRoot(
      GoBuckConfig goBuckConfig,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder) {
    Optional<Path> configValue = goBuckConfig.getGoRoot();
    if (configValue.isPresent()) {
      return configValue.get();
    }

    return Paths.get(getGoEnvFromTool(goBuckConfig, processExecutor, executableFinder, "GOROOT"));
  }

  private String getGoEnvFromTool(
      GoBuckConfig goBuckConfig,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder,
      String env) {
    Path goTool = getGoToolPath(goBuckConfig, executableFinder);
    Optional<ImmutableMap<String, String>> goRootEnv =
        goBuckConfig.getGoRoot().map(input -> ImmutableMap.of("GOROOT", input.toString()));
    try {
      ProcessExecutor.Result goToolResult =
          processExecutor.launchAndExecute(
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

  private Path getGoToolPath(GoBuckConfig goBuckConfig, ExecutableFinder executableFinder) {
    Optional<Path> goTool = goBuckConfig.getTool();
    if (goTool.isPresent()) {
      return goTool.get();
    }

    // Try resolving it via the go root config var. We can't use goRootSupplier here since that
    // would create a recursion.
    Optional<Path> goRoot = goBuckConfig.getGoRoot();
    if (goRoot.isPresent()) {
      return goRoot.get().resolve("bin").resolve("go");
    }

    return executableFinder.getExecutable(
        DEFAULT_GO_TOOL, goBuckConfig.getDelegate().getEnvironment());
  }

  private Path getToolDir(
      GoBuckConfig goBuckConfig,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder) {
    return Paths.get(
        getGoEnvFromTool(goBuckConfig, processExecutor, executableFinder, "GOTOOLDIR"));
  }
}
