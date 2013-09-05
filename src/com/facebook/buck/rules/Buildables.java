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

import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.DirectoryTraversers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Utility methods for {@link Buildable} objects.
 */
public class Buildables {

  /** Utility class: do not instantiate. */
  private Buildables() {}

  /**
   * Helper function for {@link Buildable}s to create their lists of files for caching.
   */
  public static void addInputsToSortedSet(@Nullable String pathToDirectory,
      ImmutableSortedSet.Builder<String> inputsToConsiderForCachingPurposes,
      DirectoryTraverser traverser) {
    if (pathToDirectory == null) {
      return;
    }

    Set<String> files;
    try {
      files = DirectoryTraversers.getInstance().findFiles(
          ImmutableSet.of(pathToDirectory), traverser);
    } catch (IOException e) {
      throw new RuntimeException("Exception while traversing " + pathToDirectory + ".", e);
    }

    inputsToConsiderForCachingPurposes.addAll(files);
  }

}
