/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.jvm.core.HasClasspathDeps;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class AndroidTransitiveDependencyGraph {

  private final ImmutableSortedSet<BuildRule> rulesToTraverseForTransitiveDeps;
  private final boolean isGetAllTransitiveAndroidManifests;

  /**
   * @param deps A set of dependencies for a {@link BuildRule}, presumably one that is in the
   *     process of being constructed via its builder.
   */
  AndroidTransitiveDependencyGraph(
      ImmutableSortedSet<BuildRule> deps, boolean isGetAllTransitiveAndroidManifests) {
    this.rulesToTraverseForTransitiveDeps = deps;
    this.isGetAllTransitiveAndroidManifests = isGetAllTransitiveAndroidManifests;
  }

  public ImmutableList<SourcePath> findManifestFiles() {

    ImmutableList.Builder<SourcePath> manifestFiles = ImmutableList.builder();

    new AbstractBreadthFirstTraversal<>(rulesToTraverseForTransitiveDeps) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        if (rule instanceof AndroidResource) {
          AndroidResource androidRule = (AndroidResource) rule;
          SourcePath manifestFile = androidRule.getManifestFile();
          if (manifestFile != null) {
            manifestFiles.add(manifestFile);
          }
          return isGetAllTransitiveAndroidManifests
              ? androidRule.getDeps()
              : androidRule.getDepsForTransitiveClasspathEntries();
        }

        if (rule instanceof AndroidLibrary) {
          AndroidLibrary androidLibraryRule = (AndroidLibrary) rule;
          Optionals.addIfPresent(androidLibraryRule.getManifestFile(), manifestFiles);
          return androidLibraryRule.getDepsForTransitiveClasspathEntries();
        }

        return isGetAllTransitiveAndroidManifests && rule instanceof HasClasspathDeps
            ? ((HasClasspathDeps) rule).getDepsForTransitiveClasspathEntries()
            : ImmutableSet.of();
      }
    }.start();

    return manifestFiles.build();
  }
}
