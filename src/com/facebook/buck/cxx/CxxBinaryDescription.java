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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class CxxBinaryDescription implements Description<CxxBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("cxx_binary");

  private static final Flavor CXX_LINK_BINARY_FLAVOR = new Flavor("binary");

  private final CxxBuckConfig cxxBuckConfig;

  public CxxBinaryDescription(CxxBuckConfig cxxBuckConfig) {
    this.cxxBuckConfig = Preconditions.checkNotNull(cxxBuckConfig);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private static Path getOutputPath(BuildTarget target) {
    return BuildTargets.getBinPath(target, "%s/" + target.getShortName());
  }

  @VisibleForTesting
  protected static BuildTarget createCxxLinkTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, CXX_LINK_BINARY_FLAVOR);
  }

  @Override
  public <A extends Arg> CxxBinary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    // Extract the C/C++ sources from the constructor arg.
    ImmutableList<CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            args.srcs.or(ImmutableList.<SourcePath>of()));

    // Extract the header map from the our constructor arg.
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            args.headers.or((ImmutableList.<SourcePath>of())));

    CxxPreprocessorInput cxxPreprocessorInput = CxxDescriptionEnhancer.createHeaderBuildRules(
        params,
        resolver,
        cxxBuckConfig,
        args.preprocessorFlags.or(ImmutableList.<String>of()),
        headers);

    // Generate the rules for setting up and headers, preprocessing, and compiling the input
    // sources and return the source paths for the object files.
    ImmutableList<SourcePath> objects =
        CxxDescriptionEnhancer.createPreprocessAndCompileBuildRules(
            params,
            resolver,
            cxxBuckConfig,
            cxxPreprocessorInput,
            args.compilerFlags.or(ImmutableList.<String>of()),
            /* pic */ false,
            srcs);

    // Generate the final link rule.  We use the top-level target as the link rule's
    // target, so that it corresponds to the actual binary we build.
    Path output = getOutputPath(params.getBuildTarget());
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        resolver,
        cxxBuckConfig.getLd().or(CxxLinkables.DEFAULT_LINKER_PATH),
        cxxBuckConfig.getCxxLdFlags(),
        cxxBuckConfig.getLdFlags(),
        createCxxLinkTarget(params.getBuildTarget()),
        CxxLinkableEnhancer.LinkType.EXECUTABLE,
        Optional.<String>absent(),
        output,
        objects,
        NativeLinkable.Type.STATIC,
        params.getDeps());
    resolver.addToIndex(cxxLink);

    // Return a CxxBinary rule as our representative in the action graph, rather than the CxxLink
    // rule above for a couple reasons:
    //  1) CxxBinary extends BinaryBuildRule whereas CxxLink does not, so the former can be used
    //     as executables for genrules.
    //  2) In some cases, users add dependencies from some rules onto other binary rules, typically
    //     if the binary is executed by some test or library code at test time.  These target graph
    //     deps should *not* become build time dependencies on the CxxLink step, otherwise we'd
    //     have to wait for the dependency binary to link before we could link the dependent binary.
    //     By using another BuildRule, we can keep the original target graph dependency tree while
    //     preventing it from affecting link parallelism.
    return new CxxBinary(
        params.copyWithDeps(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(params.getDeclaredDeps())
                .add(cxxLink)
                .build(),
            params.getExtraDeps()),
        output,
        cxxLink);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @SuppressFieldNotInitialized
  public static class Arg implements ConstructorArg {
    public Optional<ImmutableList<SourcePath>> srcs;
    public Optional<ImmutableList<SourcePath>> headers;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
    public Optional<ImmutableList<String>> compilerFlags;
    public Optional<ImmutableList<String>> preprocessorFlags;
  }

}
