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
package com.facebook.buck.core.build.engine.config;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.ResourceAmountsEstimator;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractResourceAwareSchedulingInfo {

  public static final ResourceAwareSchedulingInfo NON_AWARE_SCHEDULING_INFO =
      ResourceAwareSchedulingInfo.of(
          false, ResourceAmountsEstimator.DEFAULT_AMOUNTS, ImmutableMap.of());

  public abstract boolean isResourceAwareSchedulingEnabled();

  public abstract ResourceAmounts getDefaultResourceAmounts();

  /** Map from the value of {@link BuildRule#getType()} to the required resources. */
  public abstract ImmutableMap<String, ResourceAmounts> getAmountsPerRuleType();

  public ResourceAmounts getResourceAmountsForRule(BuildRule rule) {
    if (isRuleResourceFree(rule)) {
      return ResourceAmounts.zero();
    } else {
      return getResourceAmountsForRuleOrDefaultAmounts(rule);
    }
  }

  public WeightedListeningExecutorService adjustServiceDefaultWeightsTo(
      ResourceAmounts defaultAmounts, WeightedListeningExecutorService service) {
    if (isResourceAwareSchedulingEnabled()) {
      return service.withDefaultAmounts(defaultAmounts);
    }
    return service;
  }

  private boolean isRuleResourceFree(BuildRule rule) {
    return rule.hasBuildSteps();
  }

  private ResourceAmounts getResourceAmountsForRuleOrDefaultAmounts(BuildRule rule) {
    Preconditions.checkArgument(isResourceAwareSchedulingEnabled());
    if (getAmountsPerRuleType().containsKey(rule.getType())) {
      return getAmountsPerRuleType().get(rule.getType());
    } else {
      return getDefaultResourceAmounts();
    }
  }
}
