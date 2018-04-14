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

package com.facebook.buck.features.python.toolchain.impl;

import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonInterpreter;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Suppliers;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * An implementation of {@link PythonPlatform} that lazily creates {@link PythonEnvironment} and cxx
 * library.
 *
 * <p>This should be used to avoid creating all registered Python platform.
 */
public class LazyPythonPlatform implements PythonPlatform {

  private final PythonBuckConfig pythonBuckConfig;
  private final ProcessExecutor processExecutor;
  private final Flavor flavor;
  private final String configSection;
  private final Supplier<PythonEnvironment> pythonEnvironmentSupplier;

  public LazyPythonPlatform(
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig,
      ProcessExecutor processExecutor,
      Flavor flavor,
      String configSection) {
    this.pythonBuckConfig = pythonBuckConfig;
    this.processExecutor = processExecutor;
    this.flavor = flavor;
    this.configSection = configSection;

    pythonEnvironmentSupplier =
        Suppliers.memoize(
            () -> {
              PythonInterpreter pythonInterpreter =
                  toolchainProvider.getByName(
                      PythonInterpreter.DEFAULT_NAME, PythonInterpreter.class);

              Path pythonPath = pythonInterpreter.getPythonInterpreterPath(configSection);
              PythonVersion pythonVersion =
                  getVersion(pythonBuckConfig, this.processExecutor, configSection, pythonPath);
              return new PythonEnvironment(pythonPath, pythonVersion);
            });
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

  @Override
  public Flavor getFlavor() {
    return flavor;
  }

  @Override
  public PythonEnvironment getEnvironment() {
    return pythonEnvironmentSupplier.get();
  }

  @Override
  public Optional<BuildTarget> getCxxLibrary() {
    return pythonBuckConfig.getCxxLibrary(configSection);
  }
}
