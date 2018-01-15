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

package com.facebook.buck.rules;

import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import java.io.IOException;
import org.immutables.value.Value;
import org.pf4j.PluginManager;

/**
 * Contain items used to construct a {@link KnownBuildRuleTypes} that are shared between all {@link
 * Cell} instances.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractDefaultKnownBuildRuleTypesFactory implements KnownBuildRuleTypesFactory {

  abstract ProcessExecutor getExecutor();

  abstract PluginManager getPluginManager();

  abstract SandboxExecutionStrategyFactory getSandboxExecutionStrategyFactory();

  @Override
  public KnownBuildRuleTypes create(Cell cell) throws IOException, InterruptedException {
    return KnownBuildRuleTypes.createInstance(
        cell.getBuckConfig(),
        getExecutor(),
        cell.getToolchainProvider(),
        getPluginManager(),
        getSandboxExecutionStrategyFactory());
  }
}
