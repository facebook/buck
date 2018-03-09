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

package com.facebook.buck.toolchain;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;

/**
 * A factory that is used when {@link ToolchainProvider} needs to be created without depending on a
 * specific implementation of {@link ToolchainProvider}.
 */
public interface ToolchainProviderFactory {
  ToolchainProvider create(
      BuckConfig buckConfig,
      ProjectFilesystem projectFilesystem,
      RuleKeyConfiguration ruleKeyConfiguration);
}
