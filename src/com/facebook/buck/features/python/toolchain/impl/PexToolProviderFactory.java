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

import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.PexToolProvider;
import java.util.Optional;

public class PexToolProviderFactory implements ToolchainFactory<PexToolProvider> {

  @Override
  public Optional<PexToolProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(context.getBuckConfig());
    return Optional.of(
        new DefaultPexToolProvider(
            toolchainProvider, pythonBuckConfig, context.getRuleKeyConfiguration()));
  }
}
