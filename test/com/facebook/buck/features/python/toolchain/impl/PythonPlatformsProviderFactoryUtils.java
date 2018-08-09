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

package com.facebook.buck.features.python.toolchain.impl;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonInterpreter;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;

public class PythonPlatformsProviderFactoryUtils {
  public static PythonEnvironment getPythonEnvironment(
      BuckConfig buckConfig, ProcessExecutor processExecutor, ExecutableFinder executableFinder) {
    return getDefaultPythonPlatform(buckConfig, processExecutor, executableFinder).getEnvironment();
  }

  public static PythonPlatform getDefaultPythonPlatform(
      BuckConfig buckConfig, ProcessExecutor processExecutor, ExecutableFinder executableFinder) {
    return new PythonPlatformsProviderFactory()
        .createToolchain(
            new ToolchainProviderBuilder()
                .withToolchain(
                    PythonInterpreter.DEFAULT_NAME,
                    new PythonInterpreterFromConfig(
                        new PythonBuckConfig(buckConfig), executableFinder))
                .build(),
            ToolchainCreationContext.of(
                ImmutableMap.of(),
                buckConfig,
                new FakeProjectFilesystem(),
                processExecutor,
                executableFinder,
                TestRuleKeyConfigurationFactory.create()))
        .get()
        .getPythonPlatforms()
        .getValue(PythonBuckConfig.DEFAULT_PYTHON_PLATFORM);
  }
}
