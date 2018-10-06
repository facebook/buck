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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Infer support for Cxx */
public class InferBuckConfig implements AddsToRuleKey {

  private BuckConfig delegate;

  @AddToRuleKey private Supplier<? extends Tool> clangCompiler;
  @AddToRuleKey private Supplier<? extends Tool> clangPlugin;
  @AddToRuleKey private Supplier<VersionedTool> inferVersion;

  private static final String INFER_SECTION_PREFIX = "infer";

  private static Optional<Path> getPathFromConfig(BuckConfig config, String name) {
    return config.getPath(INFER_SECTION_PREFIX, name);
  }

  private static Optional<String> getValueFromConfig(BuckConfig config, String name) {
    return config.getValue(INFER_SECTION_PREFIX, name);
  }

  public InferBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
    this.clangCompiler =
        MoreSuppliers.memoize(
            () -> {
              Optional<Path> clang_compiler = getPathFromConfig(delegate, "clang_compiler");
              Preconditions.checkState(
                  clang_compiler.isPresent(),
                  "clang_compiler path not found on the current configuration");
              return new HashedFileTool(() -> delegate.getPathSourcePath(clang_compiler.get()));
            });

    this.clangPlugin =
        MoreSuppliers.memoize(
            () -> {
              Optional<Path> clang_compiler = getPathFromConfig(delegate, "clang_plugin");
              Preconditions.checkState(
                  clang_compiler.isPresent(),
                  "clang_plugin path not found on the current configuration");
              return new HashedFileTool(() -> delegate.getPathSourcePath(clang_compiler.get()));
            });
    this.inferVersion =
        MoreSuppliers.memoize(
            () -> {
              Path topLevel = InferBuckConfig.this.getInferTopLevel();
              ProcessExecutorParams params =
                  ProcessExecutorParams.builder()
                      .setCommand(ImmutableList.of(topLevel.toString(), "--version"))
                      .build();
              Result result;
              try {
                result =
                    new DefaultProcessExecutor(Console.createNullConsole())
                        .launchAndExecute(params);
                if (result.getExitCode() != 0) {
                  throw new RuntimeException(result.getMessageForUnexpectedResult("infer version"));
                }
              } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
              }
              Optional<String> versionOutput = result.getStdout();
              if (!versionOutput.isPresent() || Strings.isNullOrEmpty(versionOutput.get())) {
                // older versions of infer output on stderr
                versionOutput = result.getStderr();
              }
              String versionString = versionOutput.orElse("").trim();
              Preconditions.checkState(!Strings.isNullOrEmpty(versionString));
              return VersionedTool.of(delegate.getPathSourcePath(topLevel), "infer", versionString);
            });
  }

  public Optional<String> getBlacklistRegex() {
    return getValueFromConfig(delegate, "blacklist_regex");
  }

  private Path getInferBin() {
    return Objects.requireNonNull(
        getPathFromConfig(this.delegate, "infer_bin").orElse(null),
        "path to infer bin/ folder not found on the current configuration");
  }

  public Path getInferTopLevel() {
    return Paths.get(InferBuckConfig.this.getInferBin().toString(), "infer");
  }
}
