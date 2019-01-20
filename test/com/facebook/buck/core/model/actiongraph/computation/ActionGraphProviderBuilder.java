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
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import javax.annotation.Nullable;

public class ActionGraphProviderBuilder {

  @Nullable private ActionGraphCache actionGraphCache;

  @Nullable private CloseableMemoizedSupplier<ForkJoinPool> poolSupplier;

  @Nullable private BuckEventBus eventBus;

  @Nullable private RuleKeyConfiguration ruleKeyConfiguration;

  @Nullable private CellProvider cellProvider;

  @Nullable private ActionGraphParallelizationMode parallelizationMode;

  @Nullable private Boolean checkActionGraphs;

  @Nullable private Boolean skipActionGraphCache;

  @Nullable private Map<IncrementalActionGraphMode, Double> incrementalActionGraphExperimentGroups;

  @Nullable private IncrementalActionGraphMode incrementalActionGraphMode;

  public ActionGraphProviderBuilder withMaxEntries(Integer maxEntries) {
    this.actionGraphCache = new ActionGraphCache(maxEntries);
    return this;
  }

  public ActionGraphProviderBuilder withActionGraphCache(ActionGraphCache actionGraphCache) {
    this.actionGraphCache = actionGraphCache;
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

  public ActionGraphProviderBuilder withParallelizationMode(
      ActionGraphParallelizationMode parallelizationMode) {
    this.parallelizationMode = parallelizationMode;
    return this;
  }

  public ActionGraphProviderBuilder withCheckActionGraphs() {
    this.checkActionGraphs = true;
    return this;
  }

  public ActionGraphProviderBuilder withSkipActionGraphCache() {
    this.skipActionGraphCache = true;
    return this;
  }

  public ActionGraphProviderBuilder withIncrementalActionGraphExperimentGroups(
      Map<IncrementalActionGraphMode, Double> incrementalActionGraphExperimentGroups) {
    this.incrementalActionGraphExperimentGroups = incrementalActionGraphExperimentGroups;
    return this;
  }

  public ActionGraphProviderBuilder withIncrementalActionGraphMode(
      IncrementalActionGraphMode incrementalActionGraphMode) {
    this.incrementalActionGraphMode = incrementalActionGraphMode;
    return this;
  }

  public ActionGraphProvider build() {
    ActionGraphCache actionGraphCache =
        this.actionGraphCache == null ? new ActionGraphCache(1) : this.actionGraphCache;
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
    ActionGraphParallelizationMode parallelizationMode =
        this.parallelizationMode == null
            ? ActionGraphParallelizationMode.DISABLED
            : this.parallelizationMode;
    boolean checkActionGraphs = this.checkActionGraphs != null && this.checkActionGraphs;
    boolean skipActionGraphCache = this.skipActionGraphCache != null && this.skipActionGraphCache;
    Map<IncrementalActionGraphMode, Double> incrementalActionGraphExperimentGroups =
        this.incrementalActionGraphExperimentGroups == null
            ? ImmutableMap.of()
            : this.incrementalActionGraphExperimentGroups;
    IncrementalActionGraphMode incrementalActionGraphMode =
        this.incrementalActionGraphMode == null
            ? IncrementalActionGraphMode.DISABLED
            : this.incrementalActionGraphMode;

    return new ActionGraphProvider(
        eventBus,
        ActionGraphFactory.create(
            eventBus,
            cellProvider,
            poolSupplier,
            parallelizationMode,
            false,
            incrementalActionGraphExperimentGroups),
        actionGraphCache,
        ruleKeyConfiguration,
        checkActionGraphs,
        skipActionGraphCache,
        incrementalActionGraphMode);
  }
}
