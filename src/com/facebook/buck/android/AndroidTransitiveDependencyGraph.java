/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class AndroidTransitiveDependencyGraph {


  private final ImmutableSortedSet<BuildRule> rulesToTraverseForTransitiveDeps;

  /**
   * @param deps A set of dependencies for a {@link BuildRule}, presumably one that is in the
   *     process of being constructed via its builder.
   */
  AndroidTransitiveDependencyGraph(ImmutableSortedSet<BuildRule> deps) {
    this.rulesToTraverseForTransitiveDeps = Preconditions.checkNotNull(deps);
  }
  public ImmutableSet<SourcePath> findManifestFiles() {

    final ImmutableSet.Builder<SourcePath> manifestFiles = ImmutableSet.builder();

    new AbstractDependencyVisitor(rulesToTraverseForTransitiveDeps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof AndroidResource) {
          AndroidResource androidRule = (AndroidResource) rule;
          SourcePath manifestFile = androidRule.getManifestFile();
          if (manifestFile != null) {
            manifestFiles.add(manifestFile);
          }
        } else if (rule instanceof AndroidLibrary) {
          AndroidLibrary androidLibraryRule = (AndroidLibrary) rule;
          Optionals.addIfPresent(androidLibraryRule.getManifestFile(), manifestFiles);
        }
        return maybeVisitAllDeps(rule, rule.getProperties().is(LIBRARY));
      }
    }.start();

    return manifestFiles.build();
  }
}
