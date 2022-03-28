/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.swift.toolchain;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A class that wraps the output from a module compilation rule. This is used to determine if we
 * need to pass this as a Swift or a Clang module.
 */
@BuckStyleValue
public abstract class ExplicitModuleOutput implements AddsToRuleKey {
  /** The name of the module. */
  @AddToRuleKey
  public abstract String getName();

  /** If this output is a .swiftmodule or a .pcm file. */
  @AddToRuleKey
  public abstract boolean getIsSwiftmodule();

  /** The path to the rules modulemap if present. */
  @AddToRuleKey
  public abstract Optional<ExplicitModuleInput> getModulemapPath();

  /** The path for this output. */
  @AddToRuleKey
  public abstract SourcePath getOutputPath();

  /** If this output is a framework. */
  @AddToRuleKey
  public abstract boolean getIsFramework();

  public Iterable<String> getClangArgs(SourcePathResolverAdapter resolver) {
    Preconditions.checkState(
        !getIsSwiftmodule(), "Trying to get clang args for a swiftmodule dependency");
    Path modulePath = resolver.getIdeallyRelativePath(getOutputPath());
    return ImmutableList.of(
        "-Xcc",
        "-fmodule-file=" + getName() + "=" + modulePath,
        "-Xcc",
        "-fmodule-map-file=" + getModulemapPath().get().resolve(resolver));
  }

  public static ExplicitModuleOutput ofSwiftmodule(
      String name, SourcePath outputPath, boolean isFramework) {
    return ImmutableExplicitModuleOutput.ofImpl(
        name, true, Optional.empty(), outputPath, isFramework);
  }

  public static ExplicitModuleOutput ofClangModule(
      String name, ExplicitModuleInput modulemapPath, SourcePath outputPath) {
    return ImmutableExplicitModuleOutput.ofImpl(
        name, false, Optional.of(modulemapPath), outputPath, false);
  }
}
