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

import com.facebook.buck.cxx.ArchiveStep;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Optional;
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
  private final HalideBuckConfig halideBuckConfig;
  private final Map<
      Pair<Flavor, HeaderVisibility>,
      ImmutableMap<BuildTarget, CxxPreprocessorInput>> cxxPreprocessorInputCache =
      Maps.newHashMap();

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSortedSet<SourceWithFlags> srcs;

  @AddToRuleKey
  private final String target;

  @AddToRuleKey
  private final Tool archiver;

  @AddToRuleKey
  private final Tool ranlib;

  @AddToRuleKey
  private final Tool halideCompiler;

  protected HalideLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      ImmutableSortedSet<SourceWithFlags> srcs,
      Tool halideCompiler,
      CxxPlatform cxxPlatform,
      HalideBuckConfig halideBuckConfig) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.srcs = srcs;
    this.halideCompiler = halideCompiler;
    this.archiver = cxxPlatform.getAr();
    this.ranlib = cxxPlatform.getRanlib();
    this.halideBuckConfig = halideBuckConfig;
    this.target = halideBuckConfig.getHalideTargetForPlatform(cxxPlatform);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    final Path outputDir = getPathToOutput();
    final Path output = outputDir.resolve(getLibraryName());
    String shortName = getBuildTarget().getShortName();
    buildableContext.recordArtifact(outputDir.resolve(getLibraryName()));
    buildableContext.recordArtifact(outputDir.resolve(shortName + ".h"));

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    commands.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDir));
    commands.add(
        new HalideCompilerStep(
            getProjectFilesystem().getRootPath(),
            halideCompiler.getEnvironment(getResolver()),
            halideCompiler.getCommandPrefix(getResolver()),
            outputDir,
            shortName,
            target));
    commands.add(
        new ArchiveStep(
            getProjectFilesystem().getRootPath(),
            archiver.getEnvironment(getResolver()),
            archiver.getCommandPrefix(getResolver()),
            output,
            ImmutableList.of(outputDir.resolve(shortName + ".o"))));
    commands.add(
        new ShellStep(getProjectFilesystem().getRootPath()) {
          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            return ImmutableList.<String>builder()
                .addAll(ranlib.getCommandPrefix(getResolver()))
                .add(output.toString())
                .build();
          }

          @Override
          public String getShortName() {
            return "ranlib";
          }
        });
    return commands.build();
  }

  @Override
  public Path getPathToOutput() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s");
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    switch (headerVisibility) {
      case PUBLIC:
        return CxxPreprocessorInput.builder()
            .from(
                CxxPreprocessables.getCxxPreprocessorInput(
                    params,
                    ruleResolver,
                    cxxPlatform.getFlavor(),
                    headerVisibility,
                    CxxPreprocessables.IncludeType.SYSTEM,
                    ImmutableMultimap.<CxxSource.Type, String>of(), /* exportedPreprocessorFlags */
                    ImmutableList.<FrameworkPath>of())) /* frameworks */
            .build();
      case PRIVATE:
        return CxxPreprocessorInput.EMPTY;
    }

    throw new RuntimeException("Invalid header visibility: " + headerVisibility);
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
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
          getCxxPreprocessorInput(cxxPlatform, headerVisibility));
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
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {
    // Create a new flavored HalideLibrary rule object for the given platform.
    HalideLibrary rule = (HalideLibrary) requireBuildRule(cxxPlatform);
    Path libPath = rule.getPathToOutput().resolve(rule.getLibraryName());
    return NativeLinkableInput.of(
        SourcePathArg.from(
            getResolver(),
            new BuildTargetSourcePath(rule.getBuildTarget(), libPath)),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return NativeLinkable.Linkage.STATIC;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.<String, SourcePath>of();
  }

  private String getLibraryName() {
    return "lib" + getBuildTarget().getShortName() + ".a";
  }

  /**
   * Get the HalideLibrary rule used to generate code for @a cxxPlatform.
   *
   * @param cxxPlatform The CxxPlatform for which to generate code.
   *
   * @return The HalideLibrary rule for the given platform.
   */
  private BuildRule requireBuildRule(CxxPlatform cxxPlatform) {
    BuildTarget target = BuildTarget.builder(params.getBuildTarget())
        .addFlavors(cxxPlatform.getFlavor())
        .build();
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(target);
    if (!rule.isPresent()) {
      rule = Optional.of((BuildRule) new HalideLibrary(
          params.copyWithChanges(
              target,
              params.getDeclaredDeps(),
              params.getExtraDeps()),
          ruleResolver,
          getResolver(),
          srcs,
          halideCompiler,
          cxxPlatform,
          halideBuckConfig));
      ruleResolver.addToIndex(rule.get());
    }
    return rule.get();
  }
}
