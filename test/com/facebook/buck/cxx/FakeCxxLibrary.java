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

package com.facebook.buck.cxx;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
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

/** Fake implementation of {@link CxxLibrary} for testing. */
public final class FakeCxxLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements AbstractCxxLibrary, NativeTestable {

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
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return CxxPreprocessorInput.builder()
        .addIncludes(
            CxxSymlinkTreeHeaders.builder()
                .setIncludeType(CxxPreprocessables.IncludeType.LOCAL)
                .setNameToPathMap(
                    ImmutableSortedMap.of(
                        Paths.get("header.h"), DefaultBuildTargetSourcePath.of(publicHeaderTarget)))
                .setRoot(DefaultBuildTargetSourcePath.of(publicHeaderSymlinkTreeTarget))
                .setIncludeRoot(
                    Either.ofRight(DefaultBuildTargetSourcePath.of(publicHeaderSymlinkTreeTarget)))
                .build())
        .build();
  }

  @Override
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return CxxPreprocessorInput.builder()
        .addIncludes(
            CxxSymlinkTreeHeaders.builder()
                .setIncludeType(CxxPreprocessables.IncludeType.LOCAL)
                .setRoot(DefaultBuildTargetSourcePath.of(privateHeaderSymlinkTreeTarget))
                .setIncludeRoot(
                    Either.ofRight(DefaultBuildTargetSourcePath.of(privateHeaderSymlinkTreeTarget)))
                .setNameToPathMap(
                    ImmutableSortedMap.of(
                        Paths.get("header.h"),
                        DefaultBuildTargetSourcePath.of(privateHeaderTarget)))
                .build())
        .build();
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, ruleResolver);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableExportedDeps(BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<NativeLinkable.LanguageExtensions> languageExtensions,
      BuildRuleResolver ruleResolver) {
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
  public NativeLinkable.Linkage getPreferredLinkage(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
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
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return ImmutableMap.of(
        sharedLibrarySoname, PathSourcePath.of(getProjectFilesystem(), sharedLibraryOutput));
  }

  @Override
  public boolean isTestedBy(BuildTarget testTarget) {
    return tests.contains(testTarget);
  }
}
