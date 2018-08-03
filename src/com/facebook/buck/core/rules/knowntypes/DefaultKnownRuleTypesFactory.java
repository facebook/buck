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

package com.facebook.buck.core.rules.knowntypes;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.google.common.collect.ImmutableList;

/**
 * An implementation of {@link KnownRuleTypesFactory} that delegates functionality to {@link
 * KnownBuildRuleTypesProvider}.
 */
public class DefaultKnownRuleTypesFactory implements KnownRuleTypesFactory {

  private final KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;
  private final ImmutableList<ConfigurationRuleDescription<?>> knownConfigurationDescriptions;

  public DefaultKnownRuleTypesFactory(
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      ImmutableList<ConfigurationRuleDescription<?>> knownConfigurationDescriptions) {
    this.knownBuildRuleTypesProvider = knownBuildRuleTypesProvider;
    this.knownConfigurationDescriptions = knownConfigurationDescriptions;
  }

  @Override
  public KnownRuleTypes create(Cell cell) {
    return KnownRuleTypes.of(knownBuildRuleTypesProvider.get(cell), knownConfigurationDescriptions);
  }
}
