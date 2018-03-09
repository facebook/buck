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

package com.facebook.buck.toolchain.impl;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.ToolchainProviderFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import org.pf4j.PluginManager;

public class DefaultToolchainProviderFactory implements ToolchainProviderFactory {

  private final PluginManager pluginManager;
  private final ImmutableMap<String, String> environment;
  private final ProcessExecutor processExecutor;
  private final ExecutableFinder executableFinder;

  public DefaultToolchainProviderFactory(
      PluginManager pluginManager,
      ImmutableMap<String, String> environment,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder) {
    this.pluginManager = pluginManager;
    this.environment = environment;
    this.processExecutor = processExecutor;
    this.executableFinder = executableFinder;
  }

  @Override
  public ToolchainProvider create(
      BuckConfig buckConfig,
      ProjectFilesystem projectFilesystem,
      RuleKeyConfiguration ruleKeyConfiguration) {
    return new DefaultToolchainProvider(
        pluginManager,
        environment,
        buckConfig,
        projectFilesystem,
        processExecutor,
        executableFinder,
        ruleKeyConfiguration);
  }
}
