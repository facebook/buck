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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.ImmutableCxxPreprocessorInputCacheKey;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleWithResolver;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class PrebuiltAppleFramework extends AbstractBuildRuleWithResolver
    implements CxxPreprocessorDep, NativeLinkable, HasOutputName {

  @AddToRuleKey(stringify = true)
  private final Path out;

  @AddToRuleKey private final NativeLinkable.Linkage preferredLinkage;

  private final BuildRuleResolver ruleResolver;

  @AddToRuleKey private final SourcePath frameworkPath;
  private final String frameworkName;
  private final Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final Optional<Pattern> supportedPlatformsRegex;

  private final Map<Pair<Flavor, Linker.LinkableDepType>, NativeLinkableInput> nativeLinkableCache =
      new HashMap<>();

  private final LoadingCache<
          CxxPreprocessables.CxxPreprocessorInputCacheKey,
          ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      transitiveCxxPreprocessorInputCache =
          CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  public PrebuiltAppleFramework(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePath frameworkPath,
      Linkage preferredLinkage,
      ImmutableSet<FrameworkPath> frameworks,
      Optional<Pattern> supportedPlatformsRegex,
      Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags) {
    super(params, pathResolver);
    this.frameworkPath = frameworkPath;
    this.ruleResolver = ruleResolver;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.preferredLinkage = preferredLinkage;
    this.frameworks = frameworks;
    this.supportedPlatformsRegex = supportedPlatformsRegex;

    BuildTarget target = params.getBuildTarget();
    this.frameworkName = pathResolver.getAbsolutePath(frameworkPath).getFileName().toString();
    this.out = BuildTargets.getGenPath(getProjectFilesystem(), target, "%s").resolve(frameworkName);
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    // This file is copied rather than symlinked so that when it is included in an archive zip and
    // unpacked on another machine, it is an ordinary file in both scenarios.
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    builder.add(MkdirStep.of(getProjectFilesystem(), out.getParent()));
    builder.add(RmStep.of(getProjectFilesystem(), out).withRecursive(true));
    builder.add(
        CopyStep.forDirectory(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(frameworkPath),
            out,
            CopyStep.DirectoryMode.CONTENTS_ONLY));

    buildableContext.recordArtifact(out);
    return builder.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), out);
  }

  @Override
  public String getOutputName() {
    return this.frameworkName;
  }

  @Override
  public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      final CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
      throws NoSuchBuildTargetException {
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

    switch (headerVisibility) {
      case PUBLIC:
        if (isPlatformSupported(cxxPlatform)) {
          builder.addAllFrameworks(frameworks);

          ruleResolver.requireRule(this.getBuildTarget());
          builder.addFrameworks(FrameworkPath.ofSourcePath(getSourcePathToOutput()));
        }
        return builder.build();
      case PRIVATE:
        return builder.build();
    }

    throw new RuntimeException("Invalid header visibility: " + headerVisibility);
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
      throws NoSuchBuildTargetException {
    return transitiveCxxPreprocessorInputCache.getUnchecked(
        ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps() {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDepsForPlatform(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return getNativeLinkableDeps();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
    return ImmutableList.of();
  }

  private NativeLinkableInput getNativeLinkableInputUncached(
      CxxPlatform cxxPlatform, Linker.LinkableDepType type) {
    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }
    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(
        StringArg.from(Preconditions.checkNotNull(exportedLinkerFlags.apply(cxxPlatform))));

    ImmutableSet.Builder<FrameworkPath> frameworkPaths = ImmutableSet.builder();
    frameworkPaths.addAll(Preconditions.checkNotNull(frameworks));

    frameworkPaths.add(FrameworkPath.ofSourcePath(getSourcePathToOutput()));
    if (type == Linker.LinkableDepType.SHARED) {
      linkerArgsBuilder.addAll(
          StringArg.from(
              "-rpath", "@loader_path/Frameworks", "-rpath", "@executable_path/Frameworks"));
    }

    final ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();
    return NativeLinkableInput.of(linkerArgs, frameworkPaths.build(), Collections.emptySet());
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform, Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    Pair<Flavor, Linker.LinkableDepType> key = new Pair<>(cxxPlatform.getFlavor(), type);
    NativeLinkableInput input = nativeLinkableCache.get(key);
    if (input == null) {
      input = getNativeLinkableInputUncached(cxxPlatform, type);
      nativeLinkableCache.put(key, input);
    }
    return input;
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return this.preferredLinkage;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    return ImmutableMap.of();
  }
}
