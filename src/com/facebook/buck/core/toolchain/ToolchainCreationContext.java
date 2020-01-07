/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.toolchain;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/** Contains objects that can be used during the creation of a toolchain. */
@BuckStyleValue
public interface ToolchainCreationContext {

  ImmutableMap<String, String> getEnvironment();

  BuckConfig getBuckConfig();

  ProjectFilesystem getFilesystem();

  ProcessExecutor getProcessExecutor();

  ExecutableFinder getExecutableFinder();

  RuleKeyConfiguration getRuleKeyConfiguration();

  static ToolchainCreationContext of(
      Map<String, ? extends String> environment,
      BuckConfig buckConfig,
      ProjectFilesystem filesystem,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder,
      RuleKeyConfiguration ruleKeyConfiguration) {
    return ImmutableToolchainCreationContext.of(
        environment,
        buckConfig,
        filesystem,
        processExecutor,
        executableFinder,
        ruleKeyConfiguration);
  }
}
