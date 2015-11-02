/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

public class GoBuckConfig {
  private static final Path DEFAULT_GO_TOOL = Paths.get("go");

  private final BuckConfig delegate;

  private Supplier<Path> goToolDirSupplier;

  public GoBuckConfig(BuckConfig delegate, final ProcessExecutor processExecutor) {
    this.delegate = delegate;

    goToolDirSupplier = Suppliers.memoize(
        new Supplier<Path>() {
          @Override
          public Path get() {
            Path goTool = getGoToolPath();
            try {
              ProcessExecutor.Result goToolResult = processExecutor.launchAndExecute(
                  ProcessExecutorParams.builder().addCommand(
                    goTool.toString(), "env", "GOTOOLDIR").build(),
                  EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_ERR),
                    /* stdin */ Optional.<String>absent(),
                    /* timeOutMs */ Optional.<Long>absent(),
                    /* timeoutHandler */ Optional.<Function<Process, Void>>absent());
              if (goToolResult.getExitCode() == 0) {
                Path toolDir = Paths.get(
                    CharMatcher.WHITESPACE.trimFrom(goToolResult.getStdout().get()));
                return toolDir;
              } else {
                throw new HumanReadableException(goToolResult.getStderr().get());
              }
            } catch (InterruptedException e) {
              throw Throwables.propagate(e);
            } catch (IOException e) {
              throw new HumanReadableException(
                  e,
                  "Could not run \"%s env GOTOOLDIR\": %s",
                  goTool);
            }
          }
        });
  }

  Supplier<Tool> getGoCompiler() {
    return getGoTool("compiler", "compile");
  }
  Supplier<Tool> getGoLinker() {
    return getGoTool("linker", "link");
  }

  ImmutableList<String> getCompilerFlags() {
    return ImmutableList.copyOf(
        Splitter.on(" ").omitEmptyStrings().split(
          delegate.getValue("go", "compiler_flags").or("")));
  }

  ImmutableList<String> getLinkerFlags() {
    return ImmutableList.copyOf(
        Splitter.on(" ").omitEmptyStrings().split(
          delegate.getValue("go", "linker_flags").or("")));
  }

  private Supplier<Tool> getGoTool(final String configName, final String toolName) {
    return new Supplier<Tool>() {
      @Override
      public Tool get() {
        Optional<Path> toolPath = delegate.getPath("go", configName);
        if (toolPath.isPresent()) {
          return new HashedFileTool(toolPath.get());
        }

        return new HashedFileTool(goToolDirSupplier.get().resolve(toolName));
      }
    };
  }

  private Path getGoToolPath() {
    Path goTool = delegate.getPath("go", "tool").or(DEFAULT_GO_TOOL);

    return new ExecutableFinder().getExecutable(goTool, delegate.getEnvironment());
  }
}
