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

import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.coercer.OCamlSource;
import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Compute transitive dependencies and generate ocaml build rules
 */
public class OCamlRuleBuilder {

  public static final BuildRuleType TYPE = new BuildRuleType("ocaml_library");
  private static final Flavor STATIC_FLAVOR = new Flavor("static");

  private OCamlRuleBuilder() {
  }

  public static Function<BuildRule, ImmutableList<String>> getLibInclude() {
    return
      new Function<BuildRule, ImmutableList<String>>() {
        @Override
        public ImmutableList<String> apply(BuildRule input) {
          if (input instanceof OCamlLibrary) {
            OCamlLibrary library = (OCamlLibrary) input;
            return ImmutableList.of(library.getIncludeLibDir().toString());
          } else {
            return ImmutableList.of();
          }
        }
      };
  }

  public static ImmutableList<SourcePath> getInput(Iterable<OCamlSource> source) {
    return ImmutableList.copyOf(
        FluentIterable.from(source)
            .transform(
                new Function<OCamlSource, SourcePath>() {
                  @Override
                  public SourcePath apply(OCamlSource input) {
                    return input.getSource();
                  }
                })
    );
  }

  public static BuildTarget createStaticLibraryBuildTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, STATIC_FLAVOR);
  }

  public static AbstractBuildRule createBuildRule(
      OCamlBuckConfig ocamlBuckConfig,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OCamlSource> srcs,
      boolean isLibrary,
      ImmutableList<String> argFlags,
      final ImmutableList<String> linkerFlags) {

    ImmutableList<String> includes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude())
        .toList();

    final FluentIterable<SourcePath> srcSourcePaths = FluentIterable.from(srcs).transform(
        OCamlSource.TO_SOURCE_PATH);
    final FluentIterable<Path> srcPaths = srcSourcePaths.transform(SourcePaths.TO_PATH);

    NativeLinkableInput linkableInput = NativeLinkables.getTransitiveNativeLinkableInput(
        ocamlBuckConfig.getLinker(),
        params.getDeps(),
        NativeLinkable.Type.STATIC,
        /* reverse */ false);

    ImmutableList<OCamlLibrary> ocamlInput = OCamlUtil.getTransitiveOCamlInput(params.getDeps());

    ImmutableList<SourcePath> allInputs =
        ImmutableList.<SourcePath>builder()
            .addAll(getInput(srcs))
            .addAll(linkableInput.getInputs())
            .build();

    BuildTarget buildTarget =
        isLibrary ? createStaticLibraryBuildTarget(params.getBuildTarget())
            : params.getBuildTarget();
    final BuildRuleParams compileParams = params.copyWithChanges(
        NativeLinkable.NATIVE_LINKABLE_TYPE,
        buildTarget,
        /* declaredDeps */ ImmutableSortedSet.copyOf(SourcePaths.filterBuildRuleInputs(allInputs)),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());

    ImmutableList<String> flags = ImmutableList.<String>builder()
        .addAll(argFlags)
        .build();

    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            FluentIterable.from(params.getDeps())
                .filter(Predicates.instanceOf(CxxPreprocessorDep.class)));

    final OCamlBuildContext ocamlContext = OCamlBuildContext.builder(ocamlBuckConfig)
        .setFlags(flags)
        .setIncludes(includes)
        .setOcamlInput(ocamlInput)
        .setLinkableInput(linkableInput)
        .setUpDirectories(buildTarget, isLibrary)
        .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
        .setInput(getInput(srcs))
        .build();

    if (isLibrary) {
      final OCamlBuild ocamlLibraryBuild = new OCamlBuild(
          compileParams,
          ocamlContext,
          ocamlBuckConfig.getCCompiler(),
          ocamlBuckConfig.getCxxCompiler());

      resolver.addToIndex(ocamlLibraryBuild);

      return new OCamlStaticLibrary(
          params,
          compileParams,
          linkerFlags,
          srcPaths,
          ocamlContext,
          ocamlLibraryBuild);
    } else {
      return new OCamlBuild(
          compileParams,
          ocamlContext,
          ocamlBuckConfig.getCCompiler(),
          ocamlBuckConfig.getCxxCompiler());
    }
  }

}
