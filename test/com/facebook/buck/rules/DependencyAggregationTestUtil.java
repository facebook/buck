/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;

public final class DependencyAggregationTestUtil {
  private DependencyAggregationTestUtil() {}

  /**
   * Return dependencies of a rule, traversing through any dependency aggregations.
   */
  public static Iterable<BuildRule> getDisaggregatedDeps(BuildRule rule) {
    return
        FluentIterable.from(rule.getDeps())
            .filter(DependencyAggregation.class)
            .transformAndConcat(new Function<DependencyAggregation, Iterable<BuildRule>>() {
              @Override
              public Iterable<BuildRule> apply(DependencyAggregation input) {
                return input.getDeps();
              }
            })
            .append(
                Iterables.filter(
                    rule.getDeps(),
                    Predicates.not(Predicates.instanceOf(DependencyAggregation.class))));
  }
}
