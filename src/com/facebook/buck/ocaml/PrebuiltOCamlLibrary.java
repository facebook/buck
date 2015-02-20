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
import com.facebook.buck.cxx.ImmutableNativeLinkableInput;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

import javax.annotation.Nullable;

class PrebuiltOCamlLibrary extends AbstractBuildRule implements OCamlLibrary {

  private final String nativeLib;
  private final String bytecodeLib;
  private final SourcePath staticNativeLibraryPath;
  private final ImmutableList<SourcePath> staticCLibraryPaths;
  private final SourcePath bytecodeLibraryPath;
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
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(
        Iterables.concat(
            staticCLibraryPaths,
            ImmutableList.of(staticNativeLibraryPath, bytecodeLibraryPath)));
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder.setReflectively("nativeLib", nativeLib)
        .setReflectively("bytecodeLib", bytecodeLib)
        .setReflectively("libPath", libPath.toString());
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
            getProjectFilesystem(),
            getBuildTarget(),
            getResolver().getPath(staticNativeLibraryPath)));
    for (SourcePath staticCLibraryPath : staticCLibraryPaths) {
      librariesBuilder.add(
          new BuildTargetSourcePath(
              getProjectFilesystem(),
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

    return ImmutableNativeLinkableInput.of(
        /* inputs */ libraries,
        /* args */ linkerArgs);
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
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }
}
