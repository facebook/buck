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

package com.facebook.buck.core.toolchain;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.util.function.Supplier;
import org.immutables.value.Value;

/** Contains objects that can be used during the creation of a toolchain. */
@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
interface AbstractToolchainCreationContext {
  @Value.Parameter
  ImmutableMap<String, String> getEnvironment();

  @Value.Parameter
  BuckConfig getBuckConfig();

  @Value.Parameter
  ProjectFilesystem getFilesystem();

  @Value.Parameter
  ProcessExecutor getProcessExecutor();

  @Value.Parameter
  ExecutableFinder getExecutableFinder();

  @Value.Parameter
  RuleKeyConfiguration getRuleKeyConfiguration();

  @Value.Parameter
  Supplier<TargetConfiguration> getTargetConfiguration();
}
