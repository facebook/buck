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
import com.facebook.buck.core.model.TargetConfiguration;

/**
 * Performs filtering using a single configuration for non-native targets and a set of allowed
 * constraint values for native targets.
 *
 * <p>A non-native target is selected if its configuration matches the given non-native
 * configuration.
 *
 * <p>A native targets are selected if its platform matches at least one set of constraint values.
 */
public class ConstraintBasedAndroidPackageableFilter implements AndroidPackageableFilter {

  private final TargetConfiguration nonNativeConfiguration;

  public ConstraintBasedAndroidPackageableFilter(TargetConfiguration nonNativeConfiguration) {
    this.nonNativeConfiguration = nonNativeConfiguration;
  }

  @Override
  public boolean shouldExcludeNonNativeTarget(BuildTarget buildTarget) {
    return !nonNativeConfiguration.equals(buildTarget.getTargetConfiguration());
  }
}
