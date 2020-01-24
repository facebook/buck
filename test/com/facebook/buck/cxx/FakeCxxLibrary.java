/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.LegacyNativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformLockedNativeLinkableGroup;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Fake implementation of {@link CxxLibraryGroup} for testing. */
public final class FakeCxxLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements AbstractCxxLibraryGroup, NativeTestable, LegacyNativeLinkableGroup {

  private final BuildTarget publicHeaderTarget;
  private final BuildTarget publicHeaderSymlinkTreeTarget;
  private final BuildTarget privateHeaderTarget;
  private final BuildTarget privateHeaderSymlinkTreeTarget;
  private final BuildRule archive;
  private final BuildRule sharedLibrary;
  private final Path sharedLibraryOutput;
  private final String sharedLibrarySoname;
  private final ImmutableSortedSet<BuildTarget> tests;

  private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
      new TransitiveCxxPreprocessorInputCache(this);
  private final PlatformLockedNativeLinkableGroup.Cache linkableCache =
      LegacyNativeLinkableGroup.getNativeLinkableCache(this);

  public FakeCxxLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildTarget publicHeaderTarget,
      BuildTarget publicHeaderSymlinkTreeTarget,
      BuildTarget privateHeaderTarget,
      BuildTarget privateHeaderSymlinkTreeTarget,
      BuildRule archive,
      BuildRule sharedLibrary,
      Path sharedLibraryOutput,
      String sharedLibrarySoname,
      ImmutableSortedSet<BuildTarget> tests) {
    super(buildTarget, projectFilesystem, params);
    this.publicHeaderTarget = publicHeaderTarget;
    this.publicHeaderSymlinkTreeTarget = publicHeaderSymlinkTreeTarget;
    this.privateHeaderTarget = privateHeaderTarget;
    this.privateHeaderSymlinkTreeTarget = privateHeaderSymlinkTreeTarget;
    this.archive = archive;
    this.sharedLibrary = sharedLibrary;
    this.sharedLibraryOutput = sharedLibraryOutput;
    this.sharedLibrarySoname = sharedLibrarySoname;
    this.tests = tests;
  }

  @Override
  public PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCompatibilityCache() {
    return linkableCache;
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return CxxPreprocessorInput.builder()
        .addIncludes(
            ImmutableCxxSymlinkTreeHeaders.of(
                CxxPreprocessables.IncludeType.LOCAL,
                DefaultBuildTargetSourcePath.of(publicHeaderSymlinkTreeTarget),
                Either.ofRight(DefaultBuildTargetSourcePath.of(publicHeaderSymlinkTreeTarget)),
                Optional.empty(),
                ImmutableSortedMap.of(
                    Paths.get("header.h"), DefaultBuildTargetSourcePath.of(publicHeaderTarget)),
                HeaderSymlinkTree.class.getName()))
        .build();
  }

  @Override
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return CxxPreprocessorInput.builder()
        .addIncludes(
            ImmutableCxxSymlinkTreeHeaders.of(
                CxxPreprocessables.IncludeType.LOCAL,
                DefaultBuildTargetSourcePath.of(privateHeaderSymlinkTreeTarget),
                Either.ofRight(DefaultBuildTargetSourcePath.of(privateHeaderSymlinkTreeTarget)),
                Optional.empty(),
                ImmutableSortedMap.of(
                    Paths.get("header.h"), DefaultBuildTargetSourcePath.of(privateHeaderTarget)),
                HeaderSymlinkTree.class.getName()))
        .build();
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
  }

  @Override
  public Iterable<NativeLinkableGroup> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkableGroup.class);
  }

  @Override
  public Iterable<NativeLinkableGroup> getNativeLinkableExportedDeps(
      BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkableGroup.class);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration) {
    return type == Linker.LinkableDepType.STATIC
        ? NativeLinkableInput.of(
            ImmutableList.of(SourcePathArg.of(archive.getSourcePathToOutput())),
            ImmutableSet.of(),
            ImmutableSet.of())
        : NativeLinkableInput.of(
            ImmutableList.of(SourcePathArg.of(sharedLibrary.getSourcePathToOutput())),
            ImmutableSet.of(),
            ImmutableSet.of());
  }

  @Override
  public NativeLinkableGroup.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    return ImmutableList.of();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {}

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return ImmutableMap.of(
        sharedLibrarySoname, PathSourcePath.of(getProjectFilesystem(), sharedLibraryOutput));
  }

  @Override
  public boolean isTestedBy(BuildTarget testTarget) {
    return tests.contains(testTarget);
  }
}
