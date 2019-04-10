/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Encapsulates the information about dexing output that must be passed to ApkBuilder. */
class DexFilesInfo implements AddsToRuleKey {
  @AddToRuleKey final SourcePath primaryDexPath;

  @AddToRuleKey
  final Either<ImmutableSortedSet<SourcePath>, DexSecondaryDexDirView> secondaryDexDirs;

  @AddToRuleKey final Optional<SourcePath> proguardTextFilesPath;

  final ImmutableMap<String, SourcePath> mapOfModuleToSecondaryDexSourcePaths;

  DexFilesInfo(
      SourcePath primaryDexPath,
      ImmutableSortedSet<SourcePath> secondaryDexDirs,
      Optional<SourcePath> proguardTextFilesPath,
      ImmutableMap<String, SourcePath> mapOfModuleToSecondaryDexSourcePaths) {
    this.primaryDexPath = primaryDexPath;
    this.secondaryDexDirs = Either.ofLeft(secondaryDexDirs);
    this.proguardTextFilesPath = proguardTextFilesPath;
    this.mapOfModuleToSecondaryDexSourcePaths = mapOfModuleToSecondaryDexSourcePaths;
  }

  DexFilesInfo(
      SourcePath primaryDexPath,
      DexSecondaryDexDirView secondaryDexDirs,
      Optional<SourcePath> proguardTextFilesPath) {
    this.primaryDexPath = primaryDexPath;
    this.secondaryDexDirs = Either.ofRight(secondaryDexDirs);
    this.proguardTextFilesPath = proguardTextFilesPath;
    this.mapOfModuleToSecondaryDexSourcePaths = ImmutableMap.of();
  }

  public ImmutableSet<Path> getSecondaryDexDirs(
      ProjectFilesystem filesystem, SourcePathResolver resolver) {
    return secondaryDexDirs.transform(
        set -> set.stream().map(resolver::getRelativePath).collect(ImmutableSet.toImmutableSet()),
        view -> view.getSecondaryDexDirs(filesystem, resolver));
  }

  public ImmutableMap<String, SourcePath> getMapOfModuleToSecondaryDexSourcePaths() {
    return this.mapOfModuleToSecondaryDexSourcePaths;
  }

  static class DexSecondaryDexDirView implements AddsToRuleKey {
    @AddToRuleKey final SourcePath rootDirectory;
    @AddToRuleKey final SourcePath subDirListing;

    DexSecondaryDexDirView(SourcePath rootDirectory, SourcePath subDirListing) {
      this.rootDirectory = rootDirectory;
      this.subDirListing = subDirListing;
    }

    ImmutableSet<Path> getSecondaryDexDirs(
        ProjectFilesystem filesystem, SourcePathResolver resolver) {
      try {
        Path resolvedRootDirectory = resolver.getRelativePath(rootDirectory);
        return filesystem.readLines(resolver.getRelativePath(subDirListing)).stream()
            .map(resolvedRootDirectory::resolve)
            .collect(ImmutableSet.toImmutableSet());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
