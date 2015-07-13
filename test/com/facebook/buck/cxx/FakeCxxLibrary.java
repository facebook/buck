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
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Fake implementation of {@link AbstractCxxLibrary} for testing.
 */
public final class FakeCxxLibrary extends AbstractCxxLibrary {
  private final BuildTarget publicHeaderTarget;
  private final BuildTarget publicHeaderSymlinkTreeTarget;
  private final Path publicHeaderSymlinkTreeRoot;
  private final BuildTarget privateHeaderTarget;
  private final BuildTarget privateHeaderSymlinkTreeTarget;
  private final Path privateHeaderSymlinkTreeRoot;
  private final BuildRule archive;
  private final Path archiveOutput;
  private final BuildRule sharedLibrary;
  private final Path sharedLibraryOutput;
  private final String sharedLibrarySoname;
  private final ImmutableSortedSet<BuildTarget> tests;

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
      Path archiveOutput,
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
    this.archiveOutput = archiveOutput;
    this.sharedLibrary = sharedLibrary;
    this.sharedLibraryOutput = sharedLibraryOutput;
    this.sharedLibrarySoname = sharedLibrarySoname;
    this.tests = tests;
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
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility) {
    ImmutableMap.Builder<BuildTarget, CxxPreprocessorInput> builder = ImmutableMap.builder();
    builder.put(getBuildTarget(), getCxxPreprocessorInput(cxxPlatform, headerVisibility));
    for (BuildRule dep : getDeps()) {
      if (dep instanceof CxxPreprocessorDep) {
        builder.putAll(
            ((CxxPreprocessorDep) dep).getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                headerVisibility));
      }
    }
    return builder.build();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {
    return type == Linker.LinkableDepType.STATIC ?
        NativeLinkableInput.of(
            ImmutableList.<SourcePath>of(
                new BuildTargetSourcePath(archive.getBuildTarget())),
            ImmutableList.of(archiveOutput.toString()),
            ImmutableSet.<Path>of()) :
        NativeLinkableInput.of(
            ImmutableList.<SourcePath>of(new BuildTargetSourcePath(sharedLibrary.getBuildTarget())),
            ImmutableList.of(sharedLibraryOutput.toString()),
            ImmutableSet.<Path>of());
  }

  @Override
  public Optional<Linker.LinkableDepType> getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Optional.absent();
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
    return PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(sharedLibrarySoname),
            new PathSourcePath(getProjectFilesystem(), sharedLibraryOutput)),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return ImmutableList.of();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {}

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }

  @Override
  public boolean isTestedBy(BuildTarget testTarget) {
    return tests.contains(testTarget);
  }
}
