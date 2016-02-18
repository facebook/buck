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

import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

class PrebuiltOCamlLibrary extends AbstractBuildRule implements OCamlLibrary {

  @AddToRuleKey
  private final SourcePath staticNativeLibraryPath;
  @AddToRuleKey
  private final SourcePath staticBytecodeLibraryPath;
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
      SourcePath staticNativeLibraryPath,
      SourcePath staticBytecodeLibraryPath,
      ImmutableList<SourcePath> staticCLibraryPaths,
      SourcePath bytecodeLibraryPath,
      Path libPath,
      Path includeDir) {
    super(params, resolver);
    this.staticNativeLibraryPath = staticNativeLibraryPath;
    this.staticBytecodeLibraryPath = staticBytecodeLibraryPath;
    this.staticCLibraryPaths = staticCLibraryPaths;
    this.bytecodeLibraryPath = bytecodeLibraryPath;
    this.libPath = libPath;
    this.includeDir = includeDir;
  }

  private NativeLinkableInput getLinkableInput(SourcePath sourcePath) {
    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
    argsBuilder.add(
        new SourcePathArg(
            getResolver(),
            sourcePath));
    for (SourcePath staticCLibraryPath : staticCLibraryPaths) {
      argsBuilder.add(
          new SourcePathArg(
              getResolver(),
              staticCLibraryPath));
    }
    return NativeLinkableInput.of(
        argsBuilder.build(),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput() {
    return getLinkableInput(staticNativeLibraryPath);
  }

  @Override
  public NativeLinkableInput getBytecodeLinkableInput() {
    return getLinkableInput(staticBytecodeLibraryPath);
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
  public ImmutableSortedSet<BuildRule> getNativeCompileDeps() {
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
        .addAll(getResolver().filterBuildRuleInputs(staticBytecodeLibraryPath))
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
