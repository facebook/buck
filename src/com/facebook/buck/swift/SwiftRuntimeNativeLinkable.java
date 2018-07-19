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

package com.facebook.buck.swift;

import static com.facebook.buck.core.model.UnflavoredBuildTarget.BUILD_TARGET_PREFIX;

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.ImmutableBuildTarget;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Pseudo linkable for representing Swift runtime library's linker arguments. */
public final class SwiftRuntimeNativeLinkable implements NativeLinkable {

  private static final String SWIFT_RUNTIME = "_swift_runtime";

  private static final BuildTarget PSEUDO_BUILD_TARGET =
      ImmutableBuildTarget.of(
          ImmutableUnflavoredBuildTarget.of(
              Paths.get(SWIFT_RUNTIME),
              Optional.empty(),
              BUILD_TARGET_PREFIX + SWIFT_RUNTIME,
              SWIFT_RUNTIME));
  private final SwiftPlatform swiftPlatform;

  public SwiftRuntimeNativeLinkable(SwiftPlatform swiftPlatform) {
    this.swiftPlatform = swiftPlatform;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return PSEUDO_BUILD_TARGET;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    return ImmutableSet.of();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
      BuildRuleResolver ruleResolver) {
    return ImmutableSet.of();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<LanguageExtensions> languageExtensions,
      ActionGraphBuilder graphBuilder) {
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();

    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    populateLinkerArguments(linkerArgsBuilder, swiftPlatform, type);
    inputBuilder.addAllArgs(linkerArgsBuilder.build());

    return inputBuilder.build();
  }

  @Override
  public Linkage getPreferredLinkage(CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    ApplePlatformType type = ApplePlatformType.of(cxxPlatform.getFlavor().getName());
    if (type == ApplePlatformType.MAC) {
      return Linkage.ANY;
    }
    return Linkage.SHARED;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return ImmutableMap.of();
  }

  public static void populateLinkerArguments(
      ImmutableList.Builder<Arg> argsBuilder,
      SwiftPlatform swiftPlatform,
      Linker.LinkableDepType type) {
    ImmutableSet<Path> swiftRuntimePaths =
        type == Linker.LinkableDepType.SHARED
            ? ImmutableSet.of()
            : swiftPlatform.getSwiftStaticRuntimePaths();

    // Fall back to shared if static isn't supported on this platform.
    if (type == Linker.LinkableDepType.SHARED || swiftRuntimePaths.isEmpty()) {
      for (Path rpath : swiftPlatform.getSwiftSharedLibraryRunPaths()) {
        argsBuilder.addAll(StringArg.from("-Xlinker", "-rpath", "-Xlinker", rpath.toString()));
      }

      swiftRuntimePaths = swiftPlatform.getSwiftRuntimePaths();
    } else {
      // Static linking requires force-loading Swift libs, since the dependency
      // discovery mechanism is disabled otherwise.
      argsBuilder.addAll(StringArg.from("-Xlinker", "-force_load_swift_libs"));
    }
    for (Path swiftRuntimePath : swiftRuntimePaths) {
      argsBuilder.addAll(StringArg.from("-L", swiftRuntimePath.toString()));
    }
  }
}
