/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractNewPrebuiltCxxLibraryPaths implements PrebuiltCxxLibraryPaths {

  abstract BuildTarget getTarget();

  // New API
  abstract Optional<ImmutableList<SourcePath>> getHeaderDirs();

  abstract Optional<PatternMatchedCollection<ImmutableList<SourcePath>>> getPlatformHeaderDirs();

  abstract Optional<VersionMatchedCollection<ImmutableList<SourcePath>>> getVersionedHeaderDirs();

  abstract Optional<SourcePath> getSharedLib();

  abstract Optional<PatternMatchedCollection<SourcePath>> getPlatformSharedLib();

  abstract Optional<VersionMatchedCollection<SourcePath>> getVersionedSharedLib();

  abstract Optional<SourcePath> getStaticLib();

  abstract Optional<PatternMatchedCollection<SourcePath>> getPlatformStaticLib();

  abstract Optional<VersionMatchedCollection<SourcePath>> getVersionedStaticLib();

  abstract Optional<SourcePath> getStaticPicLib();

  abstract Optional<PatternMatchedCollection<SourcePath>> getPlatformStaticPicLib();

  abstract Optional<VersionMatchedCollection<SourcePath>> getVersionedStaticPicLib();

  private <T> Optional<T> getParameter(
      String parameter,
      Optional<T> element,
      CxxPlatform cxxPlatform,
      Optional<PatternMatchedCollection<T>> platformElement,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Optional<VersionMatchedCollection<T>> versionedElement) {

    if (element.isPresent()) {
      return element;
    }

    if (platformElement.isPresent()) {
      ImmutableList<T> matches =
          platformElement.get().getMatchingValues(cxxPlatform.getFlavor().toString());
      if (matches.size() != 1) {
        throw new HumanReadableException(
            "%s: %s: expected a single match for platform %s, but found %s",
            getTarget(), parameter, cxxPlatform.getFlavor(), matches);
      }
      return Optional.of(matches.get(0));
    }

    if (selectedVersions.isPresent() && versionedElement.isPresent()) {
      return Optional.of(
          versionedElement
              .get()
              .getOnlyMatchingValue(getTarget().toString(), selectedVersions.get()));
    }

    return Optional.empty();
  }

  Optional<SourcePath> getLibrary(
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      String parameter,
      Optional<SourcePath> lib,
      Optional<PatternMatchedCollection<SourcePath>> platformLib,
      Optional<VersionMatchedCollection<SourcePath>> versionedLib) {
    Optional<SourcePath> path =
        getParameter(parameter, lib, cxxPlatform, platformLib, selectedVersions, versionedLib);
    return path.map(
        p ->
            CxxGenruleDescription.fixupSourcePath(
                resolver, new SourcePathRuleFinder(resolver), cxxPlatform, p));
  }

  @Override
  public Optional<SourcePath> getSharedLibrary(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return getLibrary(
        resolver,
        cxxPlatform,
        selectedVersions,
        "shared_lib",
        getSharedLib(),
        getPlatformSharedLib(),
        getVersionedSharedLib());
  }

  @Override
  public Optional<SourcePath> getStaticLibrary(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return getLibrary(
        resolver,
        cxxPlatform,
        selectedVersions,
        "static_lib",
        getStaticLib(),
        getPlatformStaticLib(),
        getVersionedStaticLib());
  }

  @Override
  public Optional<SourcePath> getStaticPicLibrary(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return getLibrary(
        resolver,
        cxxPlatform,
        selectedVersions,
        "static_pic_lib",
        getStaticPicLib(),
        getPlatformStaticPicLib(),
        getVersionedStaticPicLib());
  }

  @Override
  public ImmutableList<SourcePath> getIncludeDirs(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    Optional<ImmutableList<SourcePath>> dirs =
        getParameter(
            "header_dirs",
            getHeaderDirs(),
            cxxPlatform,
            getPlatformHeaderDirs(),
            selectedVersions,
            getVersionedHeaderDirs());
    return CxxGenruleDescription.fixupSourcePaths(
        resolver, new SourcePathRuleFinder(resolver), cxxPlatform, dirs.orElse(ImmutableList.of()));
  }
}
