/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.core.model.actiongraph.computation;

import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import java.util.concurrent.ForkJoinPool;
import javax.annotation.Nullable;

public class ActionGraphProviderBuilder {

  @Nullable private Integer maxEntries;

  @Nullable private CloseableMemoizedSupplier<ForkJoinPool> poolSupplier;

  @Nullable private BuckEventBus eventBus;

  @Nullable private RuleKeyConfiguration ruleKeyConfiguration;

  @Nullable private CellProvider cellProvider;

  public ActionGraphProviderBuilder withMaxEntries(Integer maxEntries) {
    this.maxEntries = maxEntries;
    return this;
  }

  public ActionGraphProviderBuilder withPoolSupplier(
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    this.poolSupplier = poolSupplier;
    return this;
  }

  public ActionGraphProviderBuilder withEventBus(BuckEventBus eventBus) {
    this.eventBus = eventBus;
    return this;
  }

  public ActionGraphProviderBuilder withRuleKeyConfiguration(
      RuleKeyConfiguration ruleKeyConfiguration) {
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    return this;
  }

  public ActionGraphProviderBuilder withCellProvider(CellProvider cellProvider) {
    this.cellProvider = cellProvider;
    return this;
  }

  public ActionGraphProvider build() {
    int maxEntries = this.maxEntries == null ? 1 : this.maxEntries;
    CloseableMemoizedSupplier<ForkJoinPool> poolSupplier =
        this.poolSupplier == null
            ? CloseableMemoizedSupplier.of(
                () -> {
                  throw new IllegalStateException(
                      "should not use parallel executor for action graph construction in test");
                },
                ignored -> {})
            : this.poolSupplier;
    BuckEventBus eventBus =
        this.eventBus == null ? BuckEventBusForTests.newInstance() : this.eventBus;
    RuleKeyConfiguration ruleKeyConfiguration =
        this.ruleKeyConfiguration == null
            ? TestRuleKeyConfigurationFactory.create()
            : this.ruleKeyConfiguration;
    CellProvider cellProvider =
        this.cellProvider == null
            ? new TestCellBuilder().build().getCellProvider()
            : this.cellProvider;

    return new ActionGraphProvider(
        eventBus,
        ActionGraphFactory.create(eventBus, cellProvider, poolSupplier),
        new ActionGraphCache(maxEntries),
        ruleKeyConfiguration);
  }
}
