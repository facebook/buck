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
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

class OCamlStaticLibrary extends NoopBuildRule implements OCamlLibrary {
  private final BuildTarget staticLibraryTarget;
  private final ImmutableList<String> linkerFlags;
  private final ImmutableList<SourcePath> objFiles;
  private final OCamlBuildContext ocamlContext;
  private final BuildRule ocamlLibraryBuild;
  private final ImmutableSortedSet<BuildRule> compileDeps;
  private final ImmutableSortedSet<BuildRule> bytecodeCompileDeps;
  private final ImmutableSortedSet<BuildRule> bytecodeLinkDeps;

  public OCamlStaticLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      BuildRuleParams compileParams,
      ImmutableList<String> linkerFlags,
      ImmutableList<SourcePath> objFiles,
      OCamlBuildContext ocamlContext,
      BuildRule ocamlLibraryBuild,
      ImmutableSortedSet<BuildRule> compileDeps,
      ImmutableSortedSet<BuildRule> bytecodeCompileDeps,
      ImmutableSortedSet<BuildRule> bytecodeLinkDeps) {
    super(params, resolver);
    this.linkerFlags = linkerFlags;
    this.objFiles = objFiles;
    this.ocamlContext = ocamlContext;
    this.ocamlLibraryBuild = ocamlLibraryBuild;
    this.compileDeps = compileDeps;
    this.bytecodeCompileDeps = bytecodeCompileDeps;
    this.bytecodeLinkDeps = bytecodeLinkDeps;
    staticLibraryTarget = OCamlRuleBuilder.createStaticLibraryBuildTarget(
        compileParams.getBuildTarget());
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {

    Preconditions.checkArgument(
        type == Linker.LinkableDepType.STATIC,
        "Only supporting static linking in OCaml");

    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();

    // Add linker flags.
    inputBuilder.addAllArgs(linkerFlags);

    // Add arg and input for static library.
    final Path staticLibraryPath =
        OCamlBuildContext.getOutputPath(
            staticLibraryTarget,
            /* isLibrary */ true);
    inputBuilder.addInputs(
        new BuildTargetSourcePath(ocamlLibraryBuild.getBuildTarget()));
    inputBuilder.addArgs(staticLibraryPath.toString());

    // Add args and inputs for C object files.
    inputBuilder.addAllInputs(objFiles);
    inputBuilder.addAllArgs(
        Iterables.transform(
            getResolver().getAllPaths(objFiles),
            Functions.toStringFunction()));

    return inputBuilder.build();
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }

  @Override
  public Path getIncludeLibDir() {
    return OCamlBuildContext.getCompileOutputDir(staticLibraryTarget, true);
  }

  @Override
  public Iterable<String> getBytecodeIncludeDirs() {
    return ocamlContext.getBytecodeIncludeDirectories();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getCompileDeps() {
    return compileDeps;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBytecodeCompileDeps() {
    return bytecodeCompileDeps;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBytecodeLinkDeps() {
    return bytecodeLinkDeps;
  }

}
