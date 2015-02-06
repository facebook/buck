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
import com.google.common.collect.Iterables;

import javax.annotation.Nullable;

public class AndroidResourceHelper {

  private AndroidResourceHelper() {}
  /**
   * Filters out the set of {@code android_resource()} dependencies from {@code deps}. As a special
   * case, if an {@code android_prebuilt_aar()} appears in the deps, the {@code android_resource()}
   * that corresponds to the AAR will also be included in the output.
   * <p>
   * Note that if we allowed developers to depend on a flavored build target (in this case, the
   * {@link AndroidPrebuiltAarGraphEnhancer#AAR_ANDROID_RESOURCE_FLAVOR} flavor), then we could
   * require them to depend on the flavored dep explicitly in their build files. Then we could
   * eliminate this special case, though it would be more burdensome for developers to have to
   * keep track of when they could depend on an ordinary build rule vs. a flavored one.
   */
  public static ImmutableSortedSet<BuildRule> androidResOnly(ImmutableSortedSet<BuildRule> deps) {
    return FluentIterable
        .from(deps)
        .transform(new Function<BuildRule, BuildRule>() {
          @Override
          @Nullable
          public BuildRule apply(BuildRule buildRule) {
            if (buildRule instanceof AndroidResource) {
              return buildRule;
            } else if (buildRule instanceof AndroidLibrary &&
                ((AndroidLibrary) buildRule).isPrebuiltAar()) {
              // An AndroidLibrary that is created via graph enhancement from an
              // android_prebuilt_aar() should always have exactly one dependency that is an
              // AndroidResource.
              return Iterables.getOnlyElement(
                  FluentIterable.from(buildRule.getDeps())
                      .filter(Predicates.instanceOf(AndroidResource.class))
                      .toList());
           }
           return null;
         }
        })
        .filter(Predicates.notNull())
        .toSortedSet(deps.comparator());
  }
}
