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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Fake implementation of {@link CxxLibrary} for testing.
 */
public final class FakeCxxLibrary
    extends NoopBuildRule
    implements AbstractCxxLibrary, NativeTestable {

  private final BuildTarget publicHeaderTarget;
  private final BuildTarget publicHeaderSymlinkTreeTarget;
  private final Path publicHeaderSymlinkTreeRoot;
  private final BuildTarget privateHeaderTarget;
  private final BuildTarget privateHeaderSymlinkTreeTarget;
  private final Path privateHeaderSymlinkTreeRoot;
  private final BuildRule archive;
  private final BuildRule sharedLibrary;
  private final Path sharedLibraryOutput;
  private final String sharedLibrarySoname;
  private final ImmutableSortedSet<BuildTarget> tests;

  private final LoadingCache<
          CxxPreprocessables.CxxPreprocessorInputCacheKey,
          ImmutableMap<BuildTarget, CxxPreprocessorInput>
        > transitiveCxxPreprocessorInputCache =
      CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  public FakeCxxLibrary(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      BuildTarget publicHeaderTarget,
      BuildTarget publicHeaderSymlinkTreeTarget,
      Path publicHeaderSymlinkTreeRoot,
      BuildTarget privateHeaderTarget,
      BuildTarget privateHeaderSymlinkTreeTarget,
      Path privateHeaderSymlinkTreeRoot,
      BuildRule archive,
      BuildRule sharedLibrary,
      Path sharedLibraryOutput,
      String sharedLibrarySoname,
      ImmutableSortedSet<BuildTarget> tests) {
    super(params, pathResolver);
    this.publicHeaderTarget = publicHeaderTarget;
    this.publicHeaderSymlinkTreeTarget = publicHeaderSymlinkTreeTarget;
    this.publicHeaderSymlinkTreeRoot = publicHeaderSymlinkTreeRoot;
    this.privateHeaderTarget = privateHeaderTarget;
    this.privateHeaderSymlinkTreeTarget = privateHeaderSymlinkTreeTarget;
    this.privateHeaderSymlinkTreeRoot = privateHeaderSymlinkTreeRoot;
    this.archive = archive;
    this.sharedLibrary = sharedLibrary;
    this.sharedLibraryOutput = sharedLibraryOutput;
    this.sharedLibrarySoname = sharedLibrarySoname;
    this.tests = tests;
  }

  @Override
  public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    return FluentIterable.from(getDeps())
        .filter(CxxPreprocessorDep.class);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
      switch (headerVisibility) {
        case PUBLIC:
          return CxxPreprocessorInput.builder()
              .addRules(publicHeaderTarget, publicHeaderSymlinkTreeTarget)
              .addIncludeRoots(publicHeaderSymlinkTreeRoot)
              .build();
        case PRIVATE:
          return CxxPreprocessorInput.builder()
              .addRules(privateHeaderTarget, privateHeaderSymlinkTreeTarget)
              .addIncludeRoots(privateHeaderSymlinkTreeRoot)
              .build();
      }
      throw new RuntimeException("Invalid header visibility value: " + headerVisibility);
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    return transitiveCxxPreprocessorInputCache.getUnchecked(
        ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
    return FluentIterable.from(getDeclaredDeps())
        .filter(NativeLinkable.class);
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
    return type == Linker.LinkableDepType.STATIC ?
        NativeLinkableInput.of(
            ImmutableList.<Arg>of(
                new SourcePathArg(
                    getResolver(),
                    new BuildTargetSourcePath(archive.getBuildTarget()))),
            ImmutableSet.<FrameworkPath>of(),
            ImmutableSet.<FrameworkPath>of()) :
        NativeLinkableInput.of(
            ImmutableList.<Arg>of(
                new SourcePathArg(
                    getResolver(),
                    new BuildTargetSourcePath(sharedLibrary.getBuildTarget()))),
            ImmutableSet.<FrameworkPath>of(),
            ImmutableSet.<FrameworkPath>of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return ImmutableList.of();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {}

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.<String, SourcePath>of(
        sharedLibrarySoname,
        new PathSourcePath(getProjectFilesystem(), sharedLibraryOutput));
  }

  @Override
  public boolean isTestedBy(BuildTarget testTarget) {
    return tests.contains(testTarget);
  }

}
