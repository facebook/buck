/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

class PrebuiltOcamlLibrary extends OcamlLibrary {

  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final Optional<SourcePath> staticNativeLibraryPath;
  @AddToRuleKey private final SourcePath staticBytecodeLibraryPath;
  @AddToRuleKey private final ImmutableList<SourcePath> staticCLibraryPaths;
  @AddToRuleKey private final ImmutableList<SourcePath> staticNativeCLibraryPaths;
  @AddToRuleKey private final ImmutableList<SourcePath> staticBytecodeCLibraryPaths;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final SourcePath bytecodeLibraryPath;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey(stringify = true)
  private final Path libPath;

  private final Path includeDir;

  private final CxxDeps deps;

  public PrebuiltOcamlLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      Optional<SourcePath> staticNativeLibraryPath,
      SourcePath staticBytecodeLibraryPath,
      ImmutableList<SourcePath> staticCLibraryPaths,
      ImmutableList<SourcePath> staticNativeCLibraryPaths,
      ImmutableList<SourcePath> staticBytecodeCLibraryPaths,
      SourcePath bytecodeLibraryPath,
      Path libPath,
      Path includeDir,
      CxxDeps deps) {
    super(buildTarget, projectFilesystem, params);
    this.ruleFinder = ruleFinder;
    this.staticNativeLibraryPath = staticNativeLibraryPath;
    this.staticBytecodeLibraryPath = staticBytecodeLibraryPath;
    this.staticCLibraryPaths = staticCLibraryPaths;
    this.staticNativeCLibraryPaths = staticNativeCLibraryPaths;
    this.staticBytecodeCLibraryPaths = staticBytecodeCLibraryPaths;
    this.bytecodeLibraryPath = bytecodeLibraryPath;
    this.libPath = libPath;
    this.includeDir = includeDir;
    this.deps = deps;
  }

  private NativeLinkableInput getLinkableInput(
      SourcePath sourcePath, ImmutableList<SourcePath> cLibPaths) {
    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
    argsBuilder.add(SourcePathArg.of(sourcePath));
    for (SourcePath staticCLibraryPath : staticCLibraryPaths) {
      argsBuilder.add(SourcePathArg.of(staticCLibraryPath));
    }
    for (SourcePath staticCLibraryPath : cLibPaths) {
      argsBuilder.add(SourcePathArg.of(staticCLibraryPath));
    }
    return NativeLinkableInput.of(argsBuilder.build(), ImmutableSet.of(), ImmutableSet.of());
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(OcamlPlatform platform) {
    if (staticNativeLibraryPath.isPresent()) {
      return getLinkableInput(staticNativeLibraryPath.get(), staticNativeCLibraryPaths);
    } else {
      return NativeLinkableInput.of(ImmutableList.of(), ImmutableSet.of(), ImmutableSet.of());
    }
  }

  @Override
  public NativeLinkableInput getBytecodeLinkableInput(OcamlPlatform platform) {
    return getLinkableInput(staticBytecodeLibraryPath, staticBytecodeCLibraryPaths);
  }

  @Override
  public Path getIncludeLibDir(OcamlPlatform platform) {
    return includeDir;
  }

  @Override
  public Iterable<String> getBytecodeIncludeDirs(OcamlPlatform platform) {
    return ImmutableList.of(includeDir.toString());
  }

  @Override
  public ImmutableSortedSet<BuildRule> getNativeCompileDeps(OcamlPlatform platform) {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBytecodeCompileDeps(OcamlPlatform platform) {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBytecodeLinkDeps(OcamlPlatform platform) {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(ruleFinder.filterBuildRuleInputs(ImmutableList.of(bytecodeLibraryPath)))
        .addAll(ruleFinder.filterBuildRuleInputs(staticBytecodeLibraryPath))
        .build();
  }

  @Override
  public Iterable<BuildRule> getOcamlLibraryDeps(OcamlPlatform platform) {
    return deps.get(ruleFinder.getRuleResolver(), platform.getCxxPlatform());
  }
}
