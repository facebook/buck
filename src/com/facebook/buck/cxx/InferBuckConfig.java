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

package com.facebook.buck.cxx;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;

public class InferBuckConfig implements RuleKeyAppendable {

  private BuckConfig delegate;

  private Supplier<Tool> clangCompiler;
  private Supplier<Tool> clangPlugin;
  private Supplier<VersionedTool> inferVersion;

  private static final String INFER_SECTION_PREFIX = "infer";

  private ProcessExecutor createProcessExecutor(PrintStream stdout, PrintStream stderr) {
    return new ProcessExecutor(
        new Console(
            Verbosity.SILENT,
            stdout,
            stderr,
            Ansi.withoutTty()));
  }

  private static Optional<Path> getPathFromConfig(BuckConfig config, String name) {
    return config.getPath(INFER_SECTION_PREFIX, name);
  }

  private static Optional<String> getValueFromConfig(BuckConfig config, String name) {
    return config.getValue(INFER_SECTION_PREFIX, name);
  }

  public InferBuckConfig(final BuckConfig delegate) {
    this.delegate = delegate;
    this.clangCompiler = Suppliers.memoize(
        new Supplier<Tool>() {
          @Override
          public Tool get() {
            return HashedFileTool.FROM_PATH.apply(
                Preconditions.checkNotNull(
                    getPathFromConfig(delegate, "clang_compiler").orNull(),
                    "clang_compiler path not found on the current configuration"));
          }
        }
    );

    this.clangPlugin = Suppliers.memoize(
        new Supplier<Tool>() {
          @Override
          public Tool get() {
            return HashedFileTool.FROM_PATH.apply(
                Preconditions.checkNotNull(
                    getPathFromConfig(delegate, "clang_plugin").orNull(),
                    "clang_plugin path not found on the current configuration"));
          }
        }
    );

    this.inferVersion = Suppliers.memoize(
        new Supplier<VersionedTool>() {
          @Override
          public VersionedTool get() {
            Path topLevel = InferBuckConfig.this.getInferTopLevel();
            ProcessExecutorParams params = ProcessExecutorParams.builder()
                .setCommand(ImmutableList.of(topLevel.toString(), "--version"))
                .build();
            ProcessExecutor.Result result;
            try (
                PrintStream stdout = new PrintStream(new ByteArrayOutputStream());
                PrintStream stderr = new PrintStream(new ByteArrayOutputStream())) {
              result = createProcessExecutor(stdout, stderr).launchAndExecute(params);
              if (result.getExitCode() != 0) {
                throw new RuntimeException("Failed getting infer version: " + result.getStderr());
              }
            } catch (InterruptedException | IOException e) {
              throw new RuntimeException(e);
            }
            Optional<String> stderr = result.getStderr();
            String versionOutput = stderr.or("").trim();
            Preconditions.checkState(!Strings.isNullOrEmpty(versionOutput));
            return new VersionedTool(topLevel, ImmutableList.<String>of(), "infer", versionOutput);
          }
        });
  }

  public Optional<String> getBlacklistRegex() {
    return getValueFromConfig(delegate, "blacklist_regex");
  }

  private Path getInferBin() {
    return Preconditions.checkNotNull(
        getPathFromConfig(this.delegate, "infer_bin").orNull(),
        "path to infer bin/ folder not found on the current configuration");
  }

  public Path getInferTopLevel() {
    return Paths.get(InferBuckConfig.this.getInferBin().toString(), "infer");
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively("infer-version", inferVersion.get())
        .setReflectively("clang-compiler", clangCompiler.get())
        .setReflectively("clang-plugin", clangPlugin.get());
  }
}
