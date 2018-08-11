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

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class PythonPlatformsProviderFactory implements ToolchainFactory<PythonPlatformsProvider> {

  @Override
  public Optional<PythonPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(context.getBuckConfig());
    ImmutableList<PythonPlatform> pythonPlatformsList =
        getPythonPlatforms(toolchainProvider, pythonBuckConfig, context.getProcessExecutor());
    FlavorDomain<PythonPlatform> pythonPlatforms =
        FlavorDomain.from("Python Platform", pythonPlatformsList);
    return Optional.of(PythonPlatformsProvider.of(pythonPlatforms));
  }

  /**
   * Constructs set of Python platform flavors given in a .buckconfig file, as is specified by
   * section names of the form python#{flavor name}.
   */
  public ImmutableList<PythonPlatform> getPythonPlatforms(
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig,
      ProcessExecutor processExecutor) {
    ImmutableList.Builder<PythonPlatform> builder = ImmutableList.builder();

    // Add the python platform described in the top-level section first.
    builder.add(getDefaultPythonPlatform(toolchainProvider, pythonBuckConfig, processExecutor));

    pythonBuckConfig
        .getPythonPlatformSections()
        .forEach(
            section ->
                builder.add(
                    getPythonPlatform(
                        toolchainProvider,
                        pythonBuckConfig,
                        processExecutor,
                        pythonBuckConfig.calculatePythonPlatformFlavorFromSection(section),
                        section)));

    return builder.build();
  }

  private PythonPlatform getPythonPlatform(
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig,
      ProcessExecutor processExecutor,
      Flavor flavor,
      String section) {
    return new LazyPythonPlatform(
        toolchainProvider, pythonBuckConfig, processExecutor, flavor, section);
  }

  @VisibleForTesting
  protected PythonPlatform getDefaultPythonPlatform(
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig,
      ProcessExecutor executor) {
    return getPythonPlatform(
        toolchainProvider,
        pythonBuckConfig,
        executor,
        pythonBuckConfig.getDefaultPythonPlatformFlavor(),
        pythonBuckConfig.getDefaultPythonPlatformSection());
  }
}
