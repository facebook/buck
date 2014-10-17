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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.python.PythonUtil;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.util.MorePaths;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class CxxPythonExtensionDescription implements
    Description<CxxPythonExtensionDescription.Arg>,
    ImplicitDepsInferringDescription<CxxPythonExtensionDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("cxx_python_extension");

  private final CxxPlatform cxxPlatform;

  public CxxPythonExtensionDescription(CxxPlatform cxxPlatform) {
    this.cxxPlatform = Preconditions.checkNotNull(cxxPlatform);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @VisibleForTesting
  protected BuildTarget getExtensionTarget(BuildTarget target) {
    return CxxDescriptionEnhancer.createSharedLibraryBuildTarget(target);
  }

  @VisibleForTesting
  protected String getExtensionName(BuildTarget target) {
    return String.format("%s.so", target.getShortNameOnly());
  }

  @VisibleForTesting
  protected Path getExtensionPath(BuildTarget target) {
    return BuildTargets.getBinPath(target, "%s/" + getExtensionName(target));
  }

  @Override
  public <A extends Arg> CxxPythonExtension createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      A args) {

    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
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

    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.createLexYaccBuildRules(
            params,
            ruleResolver,
            cxxPlatform,
            ImmutableList.<String>of(),
            lexSrcs,
            ImmutableList.<String>of(),
            yaccSrcs);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    SymlinkTree headerSymlinkTree = CxxDescriptionEnhancer.createHeaderSymlinkTreeBuildRule(
        params,
        ruleResolver,
        headers);
    CxxPreprocessorInput cxxPreprocessorInput = CxxDescriptionEnhancer.combineCxxPreprocessorInput(
        params,
        CxxPreprocessorFlags.fromArgs(
            args.preprocessorFlags,
            args.langPreprocessorFlags),
        headerSymlinkTree,
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(headers)
            .putAll(lexYaccSources.getCxxHeaders())
            .build());

    ImmutableList<SourcePath> picObjects =
        CxxDescriptionEnhancer.createPreprocessAndCompileBuildRules(
            params,
            ruleResolver,
            cxxPlatform,
            cxxPreprocessorInput,
            args.compilerFlags.or(ImmutableList.<String>of()),
            /* pic */ true,
            ImmutableMap.<String, CxxSource>builder()
                .putAll(srcs)
                .putAll(lexYaccSources.getCxxSources())
                .build());

    // Setup the rules to link the shared library.
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);
    final BuildTarget extensionTarget = getExtensionTarget(params.getBuildTarget());
    String extensionName = getExtensionName(params.getBuildTarget());
    Path extensionModule = baseModule.resolve(extensionName);
    final Path extensionPath = getExtensionPath(extensionTarget);
    CxxLink extensionRule = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        pathResolver,
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        extensionTarget,
        CxxLinkableEnhancer.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        picObjects,
        NativeLinkable.Type.SHARED,
        params.getDeps());
    ruleResolver.addToIndex(extensionRule);

    // Create the CppLibrary rule that dependents can reference from the action graph
    // to get information about this rule (e.g. how this rule contributes to the C/C++
    // preprocessor or linker).  Long-term this should probably be collapsed into the
    // TargetGraph when it becomes exposed to build rule creation.
    return new CxxPythonExtension(
        params,
        pathResolver,
        extensionModule,
        new BuildTargetSourcePath(extensionRule.getBuildTarget()),
        extensionRule);
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

    deps.add(cxxPlatform.getPythonDep().toString());

    if (!constructorArg.lexSrcs.get().isEmpty()) {
      deps.add(cxxPlatform.getLexDep().toString());
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<String> baseModule;
  }

}
