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

package com.facebook.buck.python.toolchain.impl;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.python.toolchain.PythonEnvironment;
import com.facebook.buck.python.toolchain.PythonPlatform;
import com.facebook.buck.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.python.toolchain.PythonVersion;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public class PythonPlatformsProviderFactory implements ToolchainFactory<PythonPlatformsProvider> {

  @Override
  public Optional<PythonPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    PythonBuckConfig pythonBuckConfig =
        new PythonBuckConfig(context.getBuckConfig(), context.getExecutableFinder());
    ImmutableList<PythonPlatform> pythonPlatformsList =
        getPythonPlatforms(pythonBuckConfig, context.getProcessExecutor());
    FlavorDomain<PythonPlatform> pythonPlatforms =
        FlavorDomain.from("Python Platform", pythonPlatformsList);
    return Optional.of(PythonPlatformsProvider.of(pythonPlatforms));
  }

  /**
   * Constructs set of Python platform flavors given in a .buckconfig file, as is specified by
   * section names of the form python#{flavor name}.
   */
  public ImmutableList<PythonPlatform> getPythonPlatforms(
      PythonBuckConfig pythonBuckConfig, ProcessExecutor processExecutor) {
    ImmutableList.Builder<PythonPlatform> builder = ImmutableList.builder();

    // Add the python platform described in the top-level section first.
    builder.add(getDefaultPythonPlatform(pythonBuckConfig, processExecutor));

    pythonBuckConfig
        .getPythonPlatformSections()
        .forEach(
            section ->
                builder.add(
                    getPythonPlatform(
                        pythonBuckConfig,
                        processExecutor,
                        section,
                        pythonBuckConfig.calculatePythonPlatformFlavorFromSection(section))));

    return builder.build();
  }

  private PythonPlatform getPythonPlatform(
      PythonBuckConfig pythonBuckConfig,
      ProcessExecutor processExecutor,
      String section,
      Flavor flavor) {
    return PythonPlatform.of(
        flavor,
        getPythonEnvironment(pythonBuckConfig, processExecutor, section),
        pythonBuckConfig.getCxxLibrary(section));
  }

  @VisibleForTesting
  protected PythonEnvironment getPythonEnvironment(
      PythonBuckConfig pythonBuckConfig, ProcessExecutor processExecutor, String section) {
    Path pythonPath = pythonBuckConfig.getPythonInterpreter(section);
    PythonVersion pythonVersion =
        getVersion(pythonBuckConfig, processExecutor, section, pythonPath);
    return new PythonEnvironment(pythonPath, pythonVersion);
  }

  @VisibleForTesting
  protected PythonPlatform getDefaultPythonPlatform(
      PythonBuckConfig pythonBuckConfig, ProcessExecutor executor) {
    return getPythonPlatform(
        pythonBuckConfig,
        executor,
        pythonBuckConfig.getDefaultPythonPlatformSection(),
        pythonBuckConfig.getDefaultPythonPlatformFlavor());
  }

  private PythonVersion getVersion(
      PythonBuckConfig pythonBuckConfig,
      ProcessExecutor processExecutor,
      String section,
      Path path) {

    Optional<PythonVersion> configuredVersion =
        pythonBuckConfig.getConfiguredVersion(section).map(PythonVersionFactory::fromString);
    if (configuredVersion.isPresent()) {
      return configuredVersion.get();
    }

    try {
      return PythonVersionFactory.fromInterpreter(processExecutor, path);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
