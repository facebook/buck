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

package com.facebook.buck.apple;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.nio.file.Path;

/**
 * Utility class for examining extensions of Apple bundles.
 */
public class AppleBundleExtensions {
  // Utility class, do not instantiate.
  private AppleBundleExtensions() { }

  public static final ImmutableSet<String> VALID_XCTOOL_BUNDLE_EXTENSIONS =
      ImmutableSet.of(
          AppleBundleExtension.OCTEST.toFileExtension(),
          AppleBundleExtension.XCTEST.toFileExtension());

  public static final Predicate<Path> HAS_VALID_XCTOOL_BUNDLE_EXTENSION =
      new Predicate<Path>() {
        @Override
        public boolean apply(Path path) {
          return VALID_XCTOOL_BUNDLE_EXTENSIONS.contains(Files.getFileExtension(path.toString()));
        }
      };

  public static boolean pathsHaveValidTestExtensions(Path... paths) {
    return allPathsHaveValidTestExtensions(ImmutableSet.copyOf(paths));
  }

  public static boolean allPathsHaveValidTestExtensions(Iterable<Path> paths) {
    return Iterables.all(paths, HAS_VALID_XCTOOL_BUNDLE_EXTENSION);
  }
}
