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

package com.facebook.buck.android.packageable;

import com.facebook.buck.core.model.BuildTarget;

/**
 * Encapsulates logic for filtering instances of {@link AndroidPackageable} when collecting targets
 * from transitive dependencies of an Android binary.
 *
 * <p>Targets are split in two categories: targets with native code (cxx libraries, etc.) and
 * targets with non-native code (java libraries, Android resources).
 */
public interface AndroidPackageableFilter {
  /**
   * Encapsulates the filtering logic of non-native targets (java libraries, resources, etc.)
   *
   * @return {@code true} if the given target (that represents a target with non-native code, like
   *     java library or resources) should be excluded from the collection.
   */
  boolean shouldExcludeNonNativeTarget(BuildTarget buildTarget);
}
