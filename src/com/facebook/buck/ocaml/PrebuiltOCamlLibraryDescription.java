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
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Prebuilt OCaml library
 */
public class PrebuiltOCamlLibraryDescription
    implements Description<PrebuiltOCamlLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("prebuilt_ocaml_library");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> OCamlLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      final A args) {

    final BuildTarget target = params.getBuildTarget();

    final String libDir = args.libDir.or("lib");

    final String libName = args.libName.or(target.getShortName());

    final String nativeLib = args.nativeLib.or(String.format("%s.cmxa", libName));
    final String bytecodeLib = args.bytecodeLib.or(String.format("%s.cma", libName));
    final ImmutableList<String> cLibs = args.cLibs.get();

    final Path libPath = target.getBasePath().resolve(libDir);
    final Path includeDir = libPath.resolve(args.includeDir.or(""));

    final SourcePath staticNativeLibraryPath = new PathSourcePath(libPath.resolve(nativeLib));
    final ImmutableList<SourcePath> staticCLibraryPaths =
        FluentIterable.from(cLibs)
          .transform(new Function<String, SourcePath>() {
                       @Override
                       public SourcePath apply(String input) {
                         return new PathSourcePath(libPath.resolve(input));
                       }
                     }).toList();

    final SourcePath bytecodeLibraryPath = new PathSourcePath(libPath.resolve(bytecodeLib));

    return new PrebuiltOCamlLibrary(
        params,
        new SourcePathResolver(resolver),
        nativeLib,
        bytecodeLib,
        staticNativeLibraryPath,
        staticCLibraryPaths,
        bytecodeLibraryPath,
        libPath,
        includeDir);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<String> libDir;
    public Optional<String> includeDir;
    public Optional<String> libName;
    public Optional<String> nativeLib;
    public Optional<String> bytecodeLib;
    public Optional<ImmutableList<String>> cLibs;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
