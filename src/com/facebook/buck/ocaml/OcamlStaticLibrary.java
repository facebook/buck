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

import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

class OcamlStaticLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps implements OcamlLibrary {
  private final BuildTarget staticLibraryTarget;
  private final ImmutableList<String> linkerFlags;
  private final ImmutableList<? extends SourcePath> objFiles;
  private final OcamlBuildContext ocamlContext;
  private final BuildRule ocamlLibraryBuild;
  private final ImmutableSortedSet<BuildRule> nativeCompileDeps;
  private final ImmutableSortedSet<BuildRule> bytecodeCompileDeps;
  private final ImmutableSortedSet<BuildRule> bytecodeLinkDeps;

  public OcamlStaticLibrary(
      BuildTarget buildTarget,
      BuildTarget compileBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableList<String> linkerFlags,
      ImmutableList<? extends SourcePath> objFiles,
      OcamlBuildContext ocamlContext,
      BuildRule ocamlLibraryBuild,
      ImmutableSortedSet<BuildRule> nativeCompileDeps,
      ImmutableSortedSet<BuildRule> bytecodeCompileDeps,
      ImmutableSortedSet<BuildRule> bytecodeLinkDeps) {
    super(buildTarget, projectFilesystem, params);
    this.linkerFlags = linkerFlags;
    this.objFiles = objFiles;
    this.ocamlContext = ocamlContext;
    this.ocamlLibraryBuild = ocamlLibraryBuild;
    this.nativeCompileDeps = nativeCompileDeps;
    this.bytecodeCompileDeps = bytecodeCompileDeps;
    this.bytecodeLinkDeps = bytecodeLinkDeps;
    staticLibraryTarget = OcamlRuleBuilder.createStaticLibraryBuildTarget(compileBuildTarget);
  }

  private NativeLinkableInput getLinkableInput(boolean isBytecode) {
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();

    // Add linker flags.
    inputBuilder.addAllArgs(StringArg.from(linkerFlags));

    // Add arg and input for static library.
    UnflavoredBuildTarget staticBuildTarget = staticLibraryTarget.getUnflavoredBuildTarget();
    inputBuilder.addArgs(
        SourcePathArg.of(
            ExplicitBuildTargetSourcePath.of(
                ocamlLibraryBuild.getBuildTarget(),
                isBytecode
                    ? OcamlBuildContext.getBytecodeOutputPath(
                        staticBuildTarget, getProjectFilesystem(), /* isLibrary */ true)
                    : OcamlBuildContext.getNativeOutputPath(
                        staticBuildTarget, getProjectFilesystem(), /* isLibrary */ true))));

    // Add args and inputs for C object files.
    for (SourcePath objFile : objFiles) {
      inputBuilder.addArgs(SourcePathArg.of(objFile));
    }

    return inputBuilder.build();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput() {
    return getLinkableInput(false);
  }

  @Override
  public NativeLinkableInput getBytecodeLinkableInput() {
    return getLinkableInput(true);
  }

  @Override
  public Path getIncludeLibDir() {
    return OcamlBuildContext.getCompileNativeOutputDir(
        staticLibraryTarget.getUnflavoredBuildTarget(), getProjectFilesystem(), true);
  }

  @Override
  public Iterable<String> getBytecodeIncludeDirs() {
    return ocamlContext.getBytecodeIncludeDirectories();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getNativeCompileDeps() {
    return nativeCompileDeps;
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
