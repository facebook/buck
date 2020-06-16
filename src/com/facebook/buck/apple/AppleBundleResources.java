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

package com.facebook.buck.apple;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Resources to be bundled into a bundle. */
@BuckStyleValueWithBuilder
public abstract class AppleBundleResources implements AddsToRuleKey {
  /**
   * Directories that should be copied into the bundle as directories of files with the same name.
   */
  @AddToRuleKey
  public abstract ImmutableSet<SourcePathWithAppleBundleDestination> getResourceDirs();

  /**
   * Directories whose contents should be copied into the root of the resources subdirectory.
   *
   * <p>This is useful when the directory contents are not known beforehand, such as when a rule
   * generates a directory of files.
   */
  @AddToRuleKey
  public abstract ImmutableSet<SourcePathWithAppleBundleDestination>
      getDirsContainingResourceDirs();

  /** Files that are copied to the root of the resources subdirectory. */
  @AddToRuleKey
  public abstract ImmutableSet<SourcePathWithAppleBundleDestination> getResourceFiles();

  /** Resource files with localization variants. */
  @AddToRuleKey
  public abstract ImmutableSet<SourcePath> getResourceVariantFiles();

  public Set<SourcePath> getResourceDirsForDestination(AppleBundleDestination destination) {
    return extractSourcePathsForDestination(getResourceDirs(), destination);
  }

  public Set<SourcePath> getResourceFilesForDestination(AppleBundleDestination destination) {
    return extractSourcePathsForDestination(getResourceFiles(), destination);
  }

  private static Set<SourcePath> extractSourcePathsForDestination(
      ImmutableSet<SourcePathWithAppleBundleDestination> sourcePathWithAppleBundleDestinations,
      AppleBundleDestination destination) {
    return sourcePathWithAppleBundleDestinations.stream()
        .filter(pathWithDestination -> destination == pathWithDestination.getDestination())
        .map(SourcePathWithAppleBundleDestination::getSourcePath)
        .collect(Collectors.toSet());
  }

  /** All kinds of destinations that are used by paths in this object. */
  public SortedSet<AppleBundleDestination> getAllDestinations() {
    Stream<AppleBundleDestination> result =
        getResourcesWithDestinationStream()
            .map(SourcePathWithAppleBundleDestination::getDestination);
    if (!getResourceVariantFiles().isEmpty()) {
      result = Stream.concat(result, Stream.of(AppleBundleDestination.RESOURCES));
    }
    Supplier<SortedSet<AppleBundleDestination>> supplier = TreeSet::new;
    return result.collect(Collectors.toCollection(supplier));
  }

  /** Returns all the SourcePaths from the different types of resources. */
  public Iterable<SourcePath> getAll() {
    return Stream.concat(
            getResourcesWithDestinationStream()
                .map(SourcePathWithAppleBundleDestination::getSourcePath),
            getResourceVariantFiles().stream())
        .collect(Collectors.toSet());
  }

  private Stream<SourcePathWithAppleBundleDestination> getResourcesWithDestinationStream() {
    return Stream.concat(
        getResourceDirs().stream(),
        Stream.concat(getDirsContainingResourceDirs().stream(), getResourceFiles().stream()));
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableAppleBundleResources.Builder {}
}
