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
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.python.PythonUtil;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Map;

public class CxxPythonExtensionDescription implements
    Description<CxxPythonExtensionDescription.Arg>,
    ImplicitDepsInferringDescription<CxxPythonExtensionDescription.Arg> {

  private enum Type {
    EXTENSION,
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.of(
              CxxDescriptionEnhancer.SHARED_FLAVOR, Type.EXTENSION));

  public static final BuildRuleType TYPE = BuildRuleType.of("cxx_python_extension");

  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxPythonExtensionDescription(
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @VisibleForTesting
  protected BuildTarget getExtensionTarget(BuildTarget target, Flavor platform) {
    return CxxDescriptionEnhancer.createSharedLibraryBuildTarget(target, platform);
  }

  @VisibleForTesting
  protected String getExtensionName(BuildTarget target) {
    // .so is used on OS X too (as opposed to dylib).
    return String.format("%s.so", target.getShortName());
  }

  @VisibleForTesting
  protected Path getExtensionPath(BuildTarget target, Flavor platform) {
    return BuildTargets.getGenPath(getExtensionTarget(target, platform), "%s")
        .resolve(getExtensionName(target));
  }

  private <A extends Arg> BuildRule createExtensionBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    // Extract all C/C++ sources from the constructor arg.
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(params, ruleResolver, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params,
            ruleResolver,
            cxxPlatform, args);
    ImmutableMap<String, SourcePath> lexSrcs =
        CxxDescriptionEnhancer.parseLexSources(params, ruleResolver, args);
    ImmutableMap<String, SourcePath> yaccSrcs =
        CxxDescriptionEnhancer.parseYaccSources(params, ruleResolver, args);

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
    SymlinkTree headerSymlinkTree = CxxDescriptionEnhancer.requireHeaderSymlinkTree(
        params,
        ruleResolver,
        new SourcePathResolver(ruleResolver),
        cxxPlatform,
        /* includeLexYaccHeaders */ true,
        lexSrcs,
        yaccSrcs,
        headers,
        HeaderVisibility.PRIVATE);
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            params,
            cxxPlatform,
            CxxFlags.getLanguageFlags(
                args.preprocessorFlags,
                args.platformPreprocessorFlags,
                args.langPreprocessorFlags,
                cxxPlatform),
            args.prefixHeaders.get(),
            ImmutableList.of(headerSymlinkTree),
            ImmutableSet.<Path>of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                params.getDeps()));

    ImmutableMap<String, CxxSource> allSources =
        ImmutableMap.<String, CxxSource>builder()
            .putAll(srcs)
            .putAll(lexYaccSources.getCxxSources())
            .build();

    // Generate rule to build the object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> picObjects =
        CxxSourceRuleFactory.requirePreprocessAndCompileRules(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            cxxPreprocessorInput,
            CxxFlags.getFlags(
                args.compilerFlags,
                args.platformCompilerFlags,
                cxxPlatform),
            cxxBuckConfig.getPreprocessMode(),
            allSources,
            CxxSourceRuleFactory.PicType.PIC);

    // Setup the rules to link the shared library.
    String extensionName = getExtensionName(params.getBuildTarget());
    Path extensionPath = getExtensionPath(params.getBuildTarget(), cxxPlatform.getFlavor());
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        pathResolver,
        /* extraCxxLdFlags */ ImmutableList.<String>of(),
        /* extraLdFlags */ CxxFlags.getFlags(
            args.linkerFlags,
            args.platformLinkerFlags,
            cxxPlatform),
        getExtensionTarget(params.getBuildTarget(), cxxPlatform.getFlavor()),
        Linker.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        picObjects.values(),
        Linker.LinkableDepType.SHARED,
        params.getDeps(),
        args.cxxRuntimeType,
        Optional.<SourcePath>absent());
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      A args) {

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, Type>> type;
    Optional<Map.Entry<Flavor, CxxPlatform>> platform;
    try {
      type = LIBRARY_TYPE.getFlavorAndValue(
          ImmutableSet.copyOf(params.getBuildTarget().getFlavors()));
      platform = cxxPlatforms.getFlavorAndValue(
          ImmutableSet.copyOf(params.getBuildTarget().getFlavors()));
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.  Currently, we only support building a shared lib from the
    // pre-existing static lib, which we do here.
    if (type.isPresent()) {
      Preconditions.checkState(type.get().getValue() == Type.EXTENSION);
      Preconditions.checkState(platform.isPresent());
      return createExtensionBuildRule(params, ruleResolver, platform.get().getValue(), args);
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);
    return new CxxPythonExtension(
        params,
        ruleResolver,
        pathResolver,
        baseModule.resolve(getExtensionName(params.getBuildTarget())));
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    deps.add(cxxBuckConfig.getPythonDep());

    if (constructorArg.lexSrcs.isPresent() && !constructorArg.lexSrcs.get().isEmpty()) {
      deps.add(cxxBuckConfig.getLexDep());
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<String> baseModule;
  }

}
