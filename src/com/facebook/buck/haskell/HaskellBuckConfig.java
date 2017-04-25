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

package com.facebook.buck.haskell;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class HaskellBuckConfig implements HaskellConfig {

  private static final String SECTION = "haskell";

  private final BuckConfig delegate;
  private final ExecutableFinder finder;

  public HaskellBuckConfig(BuckConfig delegate, ExecutableFinder finder) {
    this.delegate = delegate;
    this.finder = finder;
  }

  private Optional<ImmutableList<String>> getFlags(String field) {
    Optional<String> value = delegate.getValue(SECTION, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (!value.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(value.get().trim()));
    }
    return Optional.of(split.build());
  }

  @VisibleForTesting
  protected Optional<Path> getSystemCompiler() {
    return finder.getOptionalExecutable(Paths.get("ghc"), delegate.getEnvironment());
  }

  @Override
  public ToolProvider getCompiler() {
    Optional<ToolProvider> configuredCompiler = delegate.getToolProvider(SECTION, "compiler");
    if (configuredCompiler.isPresent()) {
      return configuredCompiler.get();
    }

    Optional<Path> systemCompiler = getSystemCompiler();
    if (systemCompiler.isPresent()) {
      return new ConstantToolProvider(new HashedFileTool(systemCompiler.get()));
    }

    throw new HumanReadableException(
        "No Haskell compiler found in .buckconfig (%s.compiler) or on system", SECTION);
  }

  private static final Integer DEFAULT_MAJOR_VERSION = 7;

  @Override
  public HaskellVersion getHaskellVersion() {
    Optional<Integer> majorVersion = delegate.getInteger(SECTION, "compiler_major_version");
    return HaskellVersion.of(majorVersion.orElse(DEFAULT_MAJOR_VERSION));
  }

  @Override
  public ImmutableList<String> getCompilerFlags() {
    return getFlags("compiler_flags").orElse(ImmutableList.of());
  }

  @Override
  public ToolProvider getLinker() {
    Optional<ToolProvider> configuredLinker = delegate.getToolProvider(SECTION, "linker");
    if (configuredLinker.isPresent()) {
      return configuredLinker.get();
    }

    Optional<Path> systemLinker = getSystemCompiler();
    if (systemLinker.isPresent()) {
      return new ConstantToolProvider(new HashedFileTool(systemLinker.get()));
    }

    throw new HumanReadableException(
        "No Haskell linker found in .buckconfig (%s.linker) or on system", SECTION);
  }

  @Override
  public ImmutableList<String> getLinkerFlags() {
    return getFlags("linker_flags").orElse(ImmutableList.of());
  }

  @VisibleForTesting
  protected Optional<Path> getSystemPackager() {
    return finder.getOptionalExecutable(Paths.get("ghc-pkg"), delegate.getEnvironment());
  }

  @Override
  public ToolProvider getPackager() {
    Optional<ToolProvider> configuredPackager = delegate.getToolProvider(SECTION, "packager");
    if (configuredPackager.isPresent()) {
      return configuredPackager.get();
    }

    Optional<Path> systemPackager = getSystemPackager();
    if (systemPackager.isPresent()) {
      return new ConstantToolProvider(new HashedFileTool(systemPackager.get()));
    }

    throw new HumanReadableException(
        "No Haskell packager found in .buckconfig (%s.compiler) or on system", SECTION);
  }

  @Override
  public boolean shouldCacheLinks() {
    return delegate.getBooleanValue(SECTION, "cache_links", true);
  }

  @Override
  public Optional<Boolean> shouldUsedOldBinaryOutputLocation() {
    return delegate.getBoolean(SECTION, "old_binary_output_location");
  }
}
