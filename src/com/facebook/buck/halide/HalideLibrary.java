/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.halide;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.Map;

public class HalideLibrary
  extends AbstractBuildRule
  implements CxxPreprocessorDep, NativeLinkable {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final Map<
    Pair<Flavor, HeaderVisibility>,
    ImmutableMap<BuildTarget, CxxPreprocessorInput>> cxxPreprocessorInputCache =
    Maps.newHashMap();

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSortedSet<SourceWithFlags> srcs;

  private final Tool halideCompiler;
  private final Path outputDir;

  protected HalideLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      ImmutableSortedSet<SourceWithFlags> srcs,
      Tool halideCompiler,
      Path outputDir) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.srcs = srcs;
    this.halideCompiler = halideCompiler;
    this.outputDir = outputDir;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    String shortName = getBuildTarget().getShortName();
    buildableContext.recordArtifact(outputDir.resolve(shortName + ".h"));
    buildableContext.recordArtifact(outputDir.resolve(shortName + ".o"));

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    commands.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDir));
    commands.add(
      new HalideCompilerStep(
        getProjectFilesystem().getRootPath(),
        halideCompiler.getCommandPrefix(getResolver()),
        outputDir,
        shortName));
    return commands.build();
  }

  @Override
  public Path getPathToOutput() {
    return outputDir;
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    switch (headerVisibility) {
      case PUBLIC:
        return CxxPreprocessorInput.builder()
          .from(
            CxxPreprocessables.getCxxPreprocessorInput(
              targetGraph,
              params,
              ruleResolver,
              cxxPlatform.getFlavor(),
              headerVisibility,
              CxxPreprocessables.IncludeType.SYSTEM,
              ImmutableMultimap.<CxxSource.Type, String>of(), /* exportedPreprocessorFlags */
              cxxPlatform,
              ImmutableList.<FrameworkPath>of())) /* frameworks */
          .build();
      case PRIVATE:
        return CxxPreprocessorInput.EMPTY;
    }

    throw new RuntimeException("Invalid header visibility: " + headerVisibility);
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    Pair<Flavor, HeaderVisibility> key = new Pair<>(
      cxxPlatform.getFlavor(),
      headerVisibility);
    ImmutableMap<BuildTarget, CxxPreprocessorInput> result =
      cxxPreprocessorInputCache.get(key);
    if (result == null) {
      ImmutableMap.Builder<BuildTarget, CxxPreprocessorInput> builder =
        ImmutableMap.builder();
      builder.put(
        getBuildTarget(),
        getCxxPreprocessorInput(targetGraph, cxxPlatform, headerVisibility));
      result = builder.build();
      cxxPreprocessorInputCache.put(key, result);
    }
    return result;
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
    return FluentIterable.from(getDeclaredDeps())
        .filter(NativeLinkable.class);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {
    Path libPath = outputDir.resolve(getBuildTarget().getShortName() + ".o");
    return NativeLinkableInput.of(
        SourcePathArg.from(getResolver(), new BuildTargetSourcePath(getBuildTarget(), libPath)),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return NativeLinkable.Linkage.STATIC;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform) {
    return ImmutableMap.<String, SourcePath>of();
  }
}
