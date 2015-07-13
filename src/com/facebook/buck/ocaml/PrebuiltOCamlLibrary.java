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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

class PrebuiltOCamlLibrary extends AbstractBuildRule implements OCamlLibrary {

  @AddToRuleKey
  private final String nativeLib;
  @AddToRuleKey
  private final String bytecodeLib;
  @AddToRuleKey
  private final SourcePath staticNativeLibraryPath;
  @AddToRuleKey
  private final ImmutableList<SourcePath> staticCLibraryPaths;
  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final SourcePath bytecodeLibraryPath;
  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey(stringify = true)
  private final Path libPath;
  private final Path includeDir;

  public PrebuiltOCamlLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      String nativeLib,
      String bytecodeLib,
      SourcePath staticNativeLibraryPath,
      ImmutableList<SourcePath> staticCLibraryPaths,
      SourcePath bytecodeLibraryPath,
      Path libPath,
      Path includeDir) {
    super(params, resolver);
    this.nativeLib = nativeLib;
    this.bytecodeLib = bytecodeLib;
    this.staticNativeLibraryPath = staticNativeLibraryPath;
    this.staticCLibraryPaths = staticCLibraryPaths;
    this.bytecodeLibraryPath = bytecodeLibraryPath;
    this.libPath = libPath;
    this.includeDir = includeDir;
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {

    Preconditions.checkArgument(
        type == Linker.LinkableDepType.STATIC,
        "Only supporting static linking in OCaml");

    Preconditions.checkState(
        bytecodeLib.equals(
            nativeLib.replaceFirst(
                OCamlCompilables.OCAML_CMXA_REGEX,
                OCamlCompilables.OCAML_CMA)),
        "Bytecode library should have the same name as native library but with a .cma extension"
    );

    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<SourcePath> librariesBuilder = ImmutableList.builder();
    librariesBuilder.add(
        new BuildTargetSourcePath(
            getBuildTarget(),
            getResolver().getPath(staticNativeLibraryPath)));
    for (SourcePath staticCLibraryPath : staticCLibraryPaths) {
      librariesBuilder.add(
          new BuildTargetSourcePath(
              getBuildTarget(),
              getResolver().getPath(staticCLibraryPath)));
    }
    final ImmutableList<SourcePath> libraries = librariesBuilder.build();

    ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.add(staticNativeLibraryPath.toString());
    linkerArgsBuilder.addAll(
        FluentIterable.from(staticCLibraryPaths)
            .transform(Functions.toStringFunction()));
    final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

    return NativeLinkableInput.of(libraries, linkerArgs, ImmutableSet.<Path>of());
  }

  @Override
  public Optional<Linker.LinkableDepType> getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Optional.absent();
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }

  @Override
  public Path getIncludeLibDir() {
    return includeDir;
  }

  @Override
  public Iterable<String> getBytecodeIncludeDirs() {
    return ImmutableList.of(includeDir.toString());
  }

  @Override
  public ImmutableSortedSet<BuildRule> getCompileDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBytecodeCompileDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBytecodeLinkDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(getResolver().filterBuildRuleInputs(ImmutableList.of(bytecodeLibraryPath)))
        .addAll(getResolver().filterBuildRuleInputs(staticNativeLibraryPath))
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return null;
  }
}
