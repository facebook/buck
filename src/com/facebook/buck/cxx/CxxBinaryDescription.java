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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class CxxBinaryDescription implements Description<CxxBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("cxx_binary");

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

  @Override
  public <A extends Arg> CxxLink createBuildRule(
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

    // Generate the rules for setting up and headers, preprocessing, and compiling the input
    // sources and return the source paths for the object files.
    ImmutableList<SourcePath> objects =
        CxxDescriptionEnhancer.createPreprocessAndCompileBuildRules(
            params,
            resolver,
            cxxBuckConfig,
            args.preprocessorFlags.or(ImmutableList.<String>of()),
            headers,
            args.compilerFlags.or(ImmutableList.<String>of()),
            srcs);

    // Generate the final link rule.  We use the top-level target as the link rule's
    // target, so that it corresponds to the actual binary we build.
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        resolver,
        cxxBuckConfig.getLd().or(CxxLinkables.DEFAULT_LINKER_PATH),
        cxxBuckConfig.getCxxLdFlags(),
        cxxBuckConfig.getLdFlags(),
        params.getBuildTarget(),
        getOutputPath(params.getBuildTarget()),
        objects,
        params.getDeps());
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
