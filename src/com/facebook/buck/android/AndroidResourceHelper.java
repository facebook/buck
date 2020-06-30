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

package com.facebook.buck.android;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;

public class AndroidResourceHelper {

  private AndroidResourceHelper() {}
  /**
   * Filters out the set of {@code android_resource()} dependencies from {@code deps}. As a special
   * case, if an {@code android_prebuilt_aar()} appears in the deps, the {@code android_resource()}
   * that corresponds to the AAR will also be included in the output.
   *
   * <p>
   */
  public static ImmutableSortedSet<BuildRule> androidResOnly(SortedSet<BuildRule> deps) {
    ImmutableSortedSet.Builder<BuildRule> buildRules =
        new ImmutableSortedSet.Builder<>(deps.comparator());
    for (BuildRule buildRule : deps) {
      if (buildRule instanceof ExportDependencies) {
        buildRules.addAll(androidResOnly(((ExportDependencies) buildRule).getExportedDeps()));
      }

      if (buildRule instanceof HasAndroidResourceDeps) {
        buildRules.add(buildRule);
      }
    }
    return buildRules.build();
  }
}
