/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Prebuilt OCaml library
 */
public class PrebuiltOcamlLibraryDescription
    implements Description<PrebuiltOcamlLibraryDescription.Arg> {

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> OcamlLibrary createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      final A args) {

    final BuildTarget target = params.getBuildTarget();

    final boolean bytecodeOnly = args.bytecodeOnly.orElse(false);

    final String libDir = args.libDir.orElse("lib");

    final String libName = args.libName.orElse(target.getShortName());

    final String nativeLib = args.nativeLib.orElse(String.format("%s.cmxa", libName));
    final String bytecodeLib = args.bytecodeLib.orElse(String.format("%s.cma", libName));
    final ImmutableList<String> cLibs = args.cLibs;

    final Path libPath = target.getBasePath().resolve(libDir);
    final Path includeDir = libPath.resolve(args.includeDir.orElse(""));

    final Optional<SourcePath> staticNativeLibraryPath = bytecodeOnly
        ? Optional.empty()
        : Optional.of(new PathSourcePath(
          params.getProjectFilesystem(),
          libPath.resolve(nativeLib)));
    final SourcePath staticBytecodeLibraryPath = new PathSourcePath(
        params.getProjectFilesystem(),
        libPath.resolve(bytecodeLib));
    final ImmutableList<SourcePath> staticCLibraryPaths =
        cLibs.stream()
            .map(input -> new PathSourcePath(params.getProjectFilesystem(), libPath.resolve(input)))
            .collect(MoreCollectors.toImmutableList());

    final SourcePath bytecodeLibraryPath = new PathSourcePath(
        params.getProjectFilesystem(),
        libPath.resolve(bytecodeLib));

    return new PrebuiltOcamlLibrary(
        params,
        new SourcePathResolver(resolver),
        staticNativeLibraryPath,
        staticBytecodeLibraryPath,
        staticCLibraryPaths,
        bytecodeLibraryPath,
        libPath,
        includeDir);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<String> libDir;
    public Optional<String> includeDir;
    public Optional<String> libName;
    public Optional<String> nativeLib;
    public Optional<String> bytecodeLib;
    public ImmutableList<String> cLibs = ImmutableList.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public Optional<Boolean> bytecodeOnly;
  }

}
