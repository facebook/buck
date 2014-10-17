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
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.MorePaths;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class CxxLibraryDescription implements
    Description<CxxLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<CxxLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("cxx_library");

  private final CxxPlatform cxxPlatform;

  public CxxLibraryDescription(CxxPlatform cxxPlatform) {
    this.cxxPlatform = Preconditions.checkNotNull(cxxPlatform);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> CxxLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Extract the C/C++ sources from the constructor arg.
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            pathResolver,
            args.srcs.or(ImmutableList.<SourcePath>of()));

    // Extract the header map from the our constructor arg.
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            pathResolver,
            args.headerNamespace.transform(MorePaths.TO_PATH)
                .or(params.getBuildTarget().getBasePath()),
            args.headers.or((ImmutableList.<SourcePath>of())));

    // Extract the lex sources.
    ImmutableMap<String, SourcePath> lexSrcs =
        pathResolver.getSourcePathNames(
            params.getBuildTarget(),
            "lexSrcs",
            args.lexSrcs.or(ImmutableList.<SourcePath>of()));

    // Extract the yacc sources.
    ImmutableMap<String, SourcePath> yaccSrcs =
        pathResolver.getSourcePathNames(
            params.getBuildTarget(),
            "yaccSrcs",
            args.yaccSrcs.or(ImmutableList.<SourcePath>of()));

    // Setup the rules to run lex/yacc.
    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.createLexYaccBuildRules(
            params,
            resolver,
            cxxPlatform,
            ImmutableList.<String>of(),
            lexSrcs,
            ImmutableList.<String>of(),
            yaccSrcs);

    // Generate all the build rules necessary for the C/C++ library, including
    // all user specified sources, and sources passed in via lex/yacc.
    return CxxDescriptionEnhancer.createCxxLibraryBuildRules(
        params,
        resolver,
        cxxPlatform,
        CxxPreprocessorFlags.fromArgs(
            args.preprocessorFlags,
            args.langPreprocessorFlags),
        CxxPreprocessorFlags.fromArgs(
            args.propagatedPpFlags,
            args.propagatedLangPpFlags),
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(headers)
            .putAll(lexYaccSources.getCxxHeaders())
            .build(),
        args.compilerFlags.or(ImmutableList.<String>of()),
        ImmutableMap.<String, CxxSource>builder()
            .putAll(srcs)
            .putAll(lexYaccSources.getCxxSources())
            .build(),
        args.linkWhole.or(false),
        args.soname);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<String> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Arg constructorArg) {
    ImmutableSet.Builder<String> deps = ImmutableSet.builder();

    if (!constructorArg.lexSrcs.get().isEmpty()) {
      deps.add(cxxPlatform.getLexDep().toString());
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<ImmutableList<String>> propagatedPpFlags;
    public Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> propagatedLangPpFlags;
    public Optional<String> soname;
    public Optional<Boolean> linkWhole;
  }

}
