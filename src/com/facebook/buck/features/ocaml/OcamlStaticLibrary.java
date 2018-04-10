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

import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.stream.Stream;

class OcamlStaticLibrary extends OcamlLibrary implements HasRuntimeDeps {
  private final ImmutableList<String> linkerFlags;
  private final ImmutableList<? extends SourcePath> objFiles;
  private final OcamlBuildContext ocamlContext;
  private final BuildRule ocamlLibraryBuild;
  private final ImmutableSortedSet<BuildRule> nativeCompileDeps;
  private final ImmutableSortedSet<BuildRule> bytecodeCompileDeps;
  private final ImmutableSortedSet<BuildRule> bytecodeLinkDeps;
  private final Iterable<BuildTarget> runtimeDeps;

  public OcamlStaticLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableList<String> linkerFlags,
      ImmutableList<? extends SourcePath> objFiles,
      OcamlBuildContext ocamlContext,
      BuildRule ocamlLibraryBuild,
      ImmutableSortedSet<BuildRule> nativeCompileDeps,
      ImmutableSortedSet<BuildRule> bytecodeCompileDeps,
      ImmutableSortedSet<BuildRule> bytecodeLinkDeps,
      Iterable<BuildTarget> runtimeDeps) {
    super(buildTarget, projectFilesystem, params);
    this.linkerFlags = linkerFlags;
    this.objFiles = objFiles;
    this.ocamlContext = ocamlContext;
    this.ocamlLibraryBuild = ocamlLibraryBuild;
    this.nativeCompileDeps = nativeCompileDeps;
    this.bytecodeCompileDeps = bytecodeCompileDeps;
    this.bytecodeLinkDeps = bytecodeLinkDeps;
    this.runtimeDeps = runtimeDeps;
  }

  private NativeLinkableInput getLinkableInput(boolean isBytecode) {
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();

    // Add linker flags.
    inputBuilder.addAllArgs(StringArg.from(linkerFlags));

    // Add arg and input for static library.
    inputBuilder.addArgs(
        SourcePathArg.of(
            ExplicitBuildTargetSourcePath.of(
                ocamlLibraryBuild.getBuildTarget(),
                isBytecode
                    ? OcamlBuildContext.getBytecodeOutputPath(
                        getBuildTarget(), getProjectFilesystem(), /* isLibrary */ true)
                    : OcamlBuildContext.getNativeOutputPath(
                        getBuildTarget(), getProjectFilesystem(), /* isLibrary */ true))));

    // Add args and inputs for C object files.
    for (SourcePath objFile : objFiles) {
      inputBuilder.addArgs(SourcePathArg.of(objFile));
    }

    return inputBuilder.build();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(OcamlPlatform platform) {
    return getLinkableInput(false);
  }

  @Override
  public NativeLinkableInput getBytecodeLinkableInput(OcamlPlatform platform) {
    return getLinkableInput(true);
  }

  @Override
  public Path getIncludeLibDir(OcamlPlatform platform) {
    return OcamlBuildContext.getCompileNativeOutputDir(
        getBuildTarget(), getProjectFilesystem(), true);
  }

  @Override
  public Iterable<String> getBytecodeIncludeDirs(OcamlPlatform platform) {
    return ocamlContext.getBytecodeIncludeDirectories();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getNativeCompileDeps(OcamlPlatform platform) {
    return nativeCompileDeps;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBytecodeCompileDeps(OcamlPlatform platform) {
    return bytecodeCompileDeps;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBytecodeLinkDeps(OcamlPlatform platform) {
    return bytecodeLinkDeps;
  }

  @Override
  public Iterable<BuildRule> getOcamlLibraryDeps(OcamlPlatform platform) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return RichStream.from(runtimeDeps);
  }
}
