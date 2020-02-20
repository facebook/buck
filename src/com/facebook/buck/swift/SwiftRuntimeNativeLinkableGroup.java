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

package com.facebook.buck.swift;

import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInfo;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformMappedCache;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Objects;

/** Pseudo linkable for representing Swift runtime library's linker arguments. */
public final class SwiftRuntimeNativeLinkableGroup implements NativeLinkableGroup {

  private static final String SWIFT_RUNTIME = "_swift_runtime";

  private static final UnconfiguredBuildTarget PSEUDO_BUILD_TARGET =
      UnconfiguredBuildTarget.of(
          BaseName.ofPath(ForwardRelativePath.of(SWIFT_RUNTIME)), SWIFT_RUNTIME);

  private final SwiftPlatform swiftPlatform;
  private final BuildTarget buildTarget;

  private final PlatformMappedCache<NativeLinkableInfo> linkableCache = new PlatformMappedCache<>();

  public SwiftRuntimeNativeLinkableGroup(
      SwiftPlatform swiftPlatform, TargetConfiguration targetConfiguration) {
    this.swiftPlatform = swiftPlatform;
    this.buildTarget = PSEUDO_BUILD_TARGET.configure(targetConfiguration);
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public NativeLinkableInfo getNativeLinkable(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return linkableCache.get(
        cxxPlatform,
        () ->
            new NativeLinkableInfo(
                getBuildTarget(),
                "swift_runtime",
                ImmutableList.of(),
                ImmutableList.of(),
                Linkage.SHARED,
                new NativeLinkableInfo.Delegate() {
                  @Override
                  public NativeLinkableInput computeInput(
                      ActionGraphBuilder graphBuilder,
                      Linker.LinkableDepType type,
                      boolean forceLinkWhole,
                      TargetConfiguration targetConfiguration) {
                    return getNativeLinkableInput(type);
                  }

                  @Override
                  public ImmutableMap<String, SourcePath> getSharedLibraries(
                      ActionGraphBuilder graphBuilder) {
                    return ImmutableMap.of();
                  }
                },
                NativeLinkableInfo.defaults().setShouldBeLinkedInAppleTestAndHost(true)) {

              // TODO: As we end up creating multiple instances of this same target per-platform,
              // override the hashcode and equals so that this works properly in sets/maps.
              @Override
              public int hashCode() {
                return Objects.hash(getBuildTarget());
              }

              @Override
              public boolean equals(Object obj) {
                if (this == obj) {
                  return true;
                }
                if (getClass() != obj.getClass()) {
                  return false;
                }
                NativeLinkableInfo other = (NativeLinkableInfo) obj;
                return Objects.equals(getBuildTarget(), other.getBuildTarget());
              }
            });
  }

  private NativeLinkableInput getNativeLinkableInput(Linker.LinkableDepType type) {
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();
    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    populateLinkerArguments(linkerArgsBuilder, swiftPlatform, type);
    inputBuilder.addAllArgs(linkerArgsBuilder.build());
    NativeLinkableInput linkableInput = inputBuilder.build();
    return linkableInput;
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

      swiftRuntimePaths = swiftPlatform.getSwiftRuntimePathsForLinking();
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
