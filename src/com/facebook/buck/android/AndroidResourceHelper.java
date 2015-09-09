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

package com.facebook.buck.android;

import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;

import javax.annotation.Nullable;

public class AndroidResourceHelper {

  private AndroidResourceHelper() {}
  /**
   * Filters out the set of {@code android_resource()} dependencies from {@code deps}. As a special
   * case, if an {@code android_prebuilt_aar()} appears in the deps, the {@code android_resource()}
   * that corresponds to the AAR will also be included in the output.
   * <p>
   */
  public static ImmutableSortedSet<BuildRule> androidResOnly(ImmutableSortedSet<BuildRule> deps) {
    return FluentIterable
        .from(deps)
        .transform(new Function<BuildRule, BuildRule>() {
          @Override
          @Nullable
          public BuildRule apply(BuildRule buildRule) {
            if (buildRule instanceof HasAndroidResourceDeps) {
              return buildRule;
            }
           return null;
         }
        })
        .filter(Predicates.notNull())
        .toSortedSet(deps.comparator());
  }
}
