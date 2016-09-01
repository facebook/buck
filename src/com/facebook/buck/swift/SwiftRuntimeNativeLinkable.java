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

import static com.facebook.buck.swift.SwiftDescriptions.isSharedRequested;

import com.facebook.buck.apple.AppleCxxPlatform;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

/**
 * Pseudo linkable for representing Swift runtime library's linker arguments.
 */
final class SwiftRuntimeNativeLinkable implements NativeLinkable {

  private static final Flavor SWIFT_RUNTIME_FLAVOR = ImmutableFlavor.of("swift_runtime");

  private final BuildTarget pseudoBuildTarget;
  private final AppleCxxPlatform appleCxxPlatform;

  SwiftRuntimeNativeLinkable(BuildTarget buildTarget, AppleCxxPlatform appleCxxPlatform) {
    this.pseudoBuildTarget = buildTarget.withAppendedFlavors(SWIFT_RUNTIME_FLAVOR);
    this.appleCxxPlatform = appleCxxPlatform;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return pseudoBuildTarget;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
    return ImmutableSet.of();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
    return ImmutableSet.of();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform, Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    boolean sharedRequested = isSharedRequested(type);
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();

    ImmutableSet<Path> swiftRuntimePaths = sharedRequested ? ImmutableSet.<Path>of() :
        appleCxxPlatform.getSwiftStaticRuntimePaths();

    // Fall back to shared if static isn't supported on this platform.
    if (sharedRequested || swiftRuntimePaths.isEmpty()) {
      inputBuilder.addAllArgs(
          StringArg.from(
              "-Xlinker",
              "-rpath",
              "-Xlinker",
              "@executable_path/Frameworks"));
      swiftRuntimePaths = appleCxxPlatform.getSwiftRuntimePaths();
    } else {
      // Static linking requires force-loading Swift libs, since the dependency
      // discovery mechanism is disabled otherwise.
      inputBuilder.addAllArgs(
          StringArg.from(
              "-Xlinker",
              "-force_load_swift_libs"));
    }
    for (Path swiftRuntimePath : swiftRuntimePaths) {
      inputBuilder.addAllArgs(StringArg.from("-L", swiftRuntimePath.toString()));
    }
    return inputBuilder.build();
  }

  @Override
  public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    return ImmutableMap.of();
  }
}
