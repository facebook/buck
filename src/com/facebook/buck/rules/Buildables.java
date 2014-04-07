/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.DirectoryTraversers;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Utility methods for {@link Buildable} objects.
 */
public class Buildables {

  /** Utility class: do not instantiate. */
  private Buildables() {}

  // TODO(mbolin): Remove or rewrite this. Code in an integration test that used this method was
  // discovered to break things because it did not relative paths relative to the project root
  // correctly.
  /**
   * Helper function for {@link Buildable}s to create their lists of files for caching.
   */
  public static void addInputsToSortedSet(
      @Nullable Path pathToDirectory,
      ImmutableSortedSet.Builder<Path> inputsToConsiderForCachingPurposes,
      DirectoryTraverser traverser) {
    if (pathToDirectory == null) {
      return;
    }

    Set<Path> files;
    try {
      files = DirectoryTraversers.getInstance().findFiles(pathToDirectory.toString(), traverser);
    } catch (IOException e) {
      throw new RuntimeException("Exception while traversing " + pathToDirectory + ".", e);
    }

    inputsToConsiderForCachingPurposes.addAll(files);
  }

  public static BuildRule createRuleFromBuildable(
      Buildable buildable,
      BuildRuleType buildRuleType,
      BuildTarget buildTarget,
      ImmutableSortedSet<BuildRule> deps,
      BuildRuleParams buildRuleParams) {
    BuildRuleParams paramsWithDeps = new BuildRuleParams(
        buildTarget,
        deps,
        buildRuleParams.getVisibilityPatterns(),
        buildRuleParams.getProjectFilesystem(),
        buildRuleParams.getRuleKeyBuilderFactory());

    return new AbstractBuildable.AnonymousBuildRule(
        buildRuleType,
        buildable,
        paramsWithDeps);
  }
}
