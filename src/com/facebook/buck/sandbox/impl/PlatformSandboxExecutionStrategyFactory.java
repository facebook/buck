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

package com.facebook.buck.sandbox.impl;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.sandbox.NoSandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxConfig;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.sandbox.darwin.DarwinSandboxExecutionStrategy;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;

/**
 * Creates {@link SandboxExecutionStrategyFactory} using the information about the current platform.
 */
public class PlatformSandboxExecutionStrategyFactory implements SandboxExecutionStrategyFactory {

  @Override
  public SandboxExecutionStrategy create(ProcessExecutor processExecutor, BuckConfig buckConfig) {
    Platform platform = Platform.detect();

    if (platform == Platform.MACOS) {
      SandboxConfig sandboxConfig = buckConfig.getView(SandboxConfig.class);
      return new DarwinSandboxExecutionStrategy(processExecutor, sandboxConfig);
    }

    return new NoSandboxExecutionStrategy();
  }
}
