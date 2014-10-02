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
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SymlinkTree;
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
    ImplicitDepsInferringDescription {

  public static final BuildRuleType TYPE = new BuildRuleType("cxx_python_extension");

  private final CxxBuckConfig cxxBuckConfig;

  public CxxPythonExtensionDescription(CxxBuckConfig cxxBuckConfig) {
    this.cxxBuckConfig = Preconditions.checkNotNull(cxxBuckConfig);
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

    // Extract the lex sources.
    ImmutableMap<String, SourcePath> lexSrcs =
        SourcePaths.getSourcePathNames(
            params.getBuildTarget(),
            "lexSrcs",
            args.lexSrcs.or(ImmutableList.<SourcePath>of()));

    // Extract the yacc sources.
    ImmutableMap<String, SourcePath> yaccSrcs =
        SourcePaths.getSourcePathNames(
            params.getBuildTarget(),
            "yaccSrcs",
            args.yaccSrcs.or(ImmutableList.<SourcePath>of()));

    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.createLexYaccBuildRules(
            params,
            resolver,
            cxxBuckConfig,
            ImmutableList.<String>of(),
            lexSrcs,
            ImmutableList.<String>of(),
            yaccSrcs);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    SymlinkTree headerSymlinkTree = CxxDescriptionEnhancer.createHeaderSymlinkTreeBuildRule(
        params,
        resolver,
        headers);
    CxxPreprocessorInput cxxPreprocessorInput = CxxDescriptionEnhancer.combineCxxPreprocessorInput(
        params,
        cxxBuckConfig,
        args.preprocessorFlags.or(ImmutableList.<String>of()),
        headerSymlinkTree,
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(headers)
            .putAll(lexYaccSources.getCxxHeaders())
            .build());

    ImmutableList<SourcePath> picObjects =
        CxxDescriptionEnhancer.createPreprocessAndCompileBuildRules(
            params,
            resolver,
            cxxBuckConfig,
            cxxPreprocessorInput,
            args.compilerFlags.or(ImmutableList.<String>of()),
            /* pic */ true,
            ImmutableList.<CxxSource>builder()
                .addAll(srcs)
                .addAll(lexYaccSources.getCxxSources())
                .build());

    // Setup the rules to link the shared library.
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);
    final BuildTarget extensionTarget = getExtensionTarget(params.getBuildTarget());
    String extensionName = getExtensionName(params.getBuildTarget());
    Path extensionModule = baseModule.resolve(extensionName);
    final Path extensionPath = getExtensionPath(extensionTarget);
    CxxLink extensionRule = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        cxxBuckConfig.getLd().or(CxxLinkables.DEFAULT_LINKER_PATH),
        cxxBuckConfig.getCxxLdFlags(),
        cxxBuckConfig.getLdFlags(),
        extensionTarget,
        CxxLinkableEnhancer.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        picObjects,
        NativeLinkable.Type.SHARED,
        params.getDeps());
    resolver.addToIndex(extensionRule);

    // Create the CppLibrary rule that dependents can reference from the action graph
    // to get information about this rule (e.g. how this rule contributes to the C/C++
    // preprocessor or linker).  Long-term this should probably be collapsed into the
    // TargetGraph when it becomes exposed to build rule creation.
    return new CxxPythonExtension(
        params,
        extensionModule,
        new BuildRuleSourcePath(extensionRule),
        extensionRule);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<String> findDepsFromParams(BuildRuleFactoryParams params) {
    ImmutableSet.Builder<String> deps = ImmutableSet.builder();

    deps.add(cxxBuckConfig.getPythonDep().toString());

    if (!params.getOptionalListAttribute("lexSrcs").isEmpty()) {
      deps.add(cxxBuckConfig.getLexDep().toString());
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<String> baseModule;
  }

}
