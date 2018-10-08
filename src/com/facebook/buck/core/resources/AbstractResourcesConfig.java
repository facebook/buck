/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.core.resources;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.ResourceAmountsEstimator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
abstract class AbstractResourcesConfig implements ConfigView<BuckConfig> {
  public static final String RESOURCES_SECTION_HEADER = "resources";
  public static final String RESOURCES_PER_RULE_SECTION_HEADER = "resources_per_rule";

  @Override
  @Value.Parameter
  public abstract BuckConfig getDelegate();

  @Value.Lazy
  public ResourceAllocationFairness getResourceAllocationFairness() {
    return getDelegate()
        .getEnum(
            RESOURCES_SECTION_HEADER,
            "resource_allocation_fairness",
            ResourceAllocationFairness.class)
        .orElse(ResourceAllocationFairness.FAIR);
  }

  @Value.Lazy
  public boolean isResourceAwareSchedulingEnabled() {
    return getDelegate()
        .getBooleanValue(RESOURCES_SECTION_HEADER, "resource_aware_scheduling_enabled", false);
  }

  @Value.Lazy
  public ImmutableMap<String, ResourceAmounts> getResourceAmountsPerRuleType() {
    ImmutableMap.Builder<String, ResourceAmounts> result = ImmutableMap.builder();
    ImmutableMap<String, String> entries =
        getDelegate().getEntriesForSection(RESOURCES_PER_RULE_SECTION_HEADER);
    for (String ruleName : entries.keySet()) {
      ImmutableList<String> configAmounts =
          getDelegate().getListWithoutComments(RESOURCES_PER_RULE_SECTION_HEADER, ruleName);
      Preconditions.checkArgument(
          configAmounts.size() == ResourceAmounts.RESOURCE_TYPE_COUNT,
          "Buck config entry [%s].%s contains %s values, but expected to contain %s values "
              + "in the following order: cpu, memory, disk_io, network_io",
          RESOURCES_PER_RULE_SECTION_HEADER,
          ruleName,
          configAmounts.size(),
          ResourceAmounts.RESOURCE_TYPE_COUNT);
      ResourceAmounts amounts =
          ResourceAmounts.of(
              Integer.parseInt(configAmounts.get(0)),
              Integer.parseInt(configAmounts.get(1)),
              Integer.parseInt(configAmounts.get(2)),
              Integer.parseInt(configAmounts.get(3)));
      result.put(ruleName, amounts);
    }
    return result.build();
  }

  @Value.Lazy
  public int getManagedThreadCount() {
    if (!isResourceAwareSchedulingEnabled()) {
      return getDelegate().getNumThreads();
    }
    return getDelegate()
        .getLong(RESOURCES_SECTION_HEADER, "managed_thread_count")
        .orElse(
            (long) getDelegate().getNumThreads() + getDelegate().getDefaultMaximumNumberOfThreads())
        .intValue();
  }

  @Value.Lazy
  public ResourceAmounts getDefaultResourceAmounts() {
    if (!isResourceAwareSchedulingEnabled()) {
      return ResourceAmounts.of(1, 0, 0, 0);
    }
    return ResourceAmounts.of(
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "default_cpu_amount")
            .orElse(ResourceAmountsEstimator.DEFAULT_CPU_AMOUNT),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "default_memory_amount")
            .orElse(ResourceAmountsEstimator.DEFAULT_MEMORY_AMOUNT),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "default_disk_io_amount")
            .orElse(ResourceAmountsEstimator.DEFAULT_DISK_IO_AMOUNT),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "default_network_io_amount")
            .orElse(ResourceAmountsEstimator.DEFAULT_NETWORK_IO_AMOUNT));
  }

  @Value.Lazy
  public ResourceAmounts getMaximumResourceAmounts() {
    ResourceAmounts estimated = ResourceAmountsEstimator.getEstimatedAmounts();
    return ResourceAmounts.of(
        getDelegate().getNumThreads(estimated.getCpu()),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "max_memory_resource")
            .orElse(estimated.getMemory()),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "max_disk_io_resource")
            .orElse(estimated.getDiskIO()),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "max_network_io_resource")
            .orElse(estimated.getNetworkIO()));
  }

  /**
   * Construct a default ConcurrencyLimit instance from this config.
   *
   * @return New instance of ConcurrencyLimit.
   */
  @Value.Lazy
  public ConcurrencyLimit getConcurrencyLimit() {
    return new ConcurrencyLimit(
        getDelegate().getNumThreads(),
        getResourceAllocationFairness(),
        getManagedThreadCount(),
        getDefaultResourceAmounts(),
        getMaximumResourceAmounts());
  }
}
