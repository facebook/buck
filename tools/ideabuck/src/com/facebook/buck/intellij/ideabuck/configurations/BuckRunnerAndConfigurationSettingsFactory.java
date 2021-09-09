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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import org.jetbrains.annotations.Nullable;

/** Factory class for creating configuration settings for Buck commands */
public class BuckRunnerAndConfigurationSettingsFactory {

  public static RunnerAndConfigurationSettings getBuckBuildConfigSettings(
      RunManager runManager, String targets, String additionalParams) {
    return createAndSetBaseConfiguration(
        new BuckBuildConfigurationType(),
        "Buck " + BuckCommand.BUILD.name() + " " + targets,
        runManager,
        targets,
        additionalParams,
        null);
  }

  public static RunnerAndConfigurationSettings getBuckRunConfigSettings(
      RunManager runManager, String targets, String additionalParams) {
    return createAndSetBaseConfiguration(
        new BuckRunConfigurationType(),
        "Buck " + BuckCommand.RUN.name() + " " + targets,
        runManager,
        targets,
        additionalParams,
        null);
  }

  public static RunnerAndConfigurationSettings getBuckTestConfigSettings(
      RunManager runManager,
      String name,
      @Nullable String targets,
      @Nullable String additionalParams,
      @Nullable String testSelectors) {
    RunnerAndConfigurationSettings settings =
        createAndSetBaseConfiguration(
            BuckTestConfigurationType.getInstance(),
            name,
            runManager,
            targets,
            additionalParams,
            null);
    if (testSelectors != null) {
      ((BuckTestConfiguration) settings.getConfiguration()).data.testSelectors = testSelectors;
    }
    return settings;
  }

  public static RunnerAndConfigurationSettings getBuckTestConfigSettings(
      RunManager runManager, String targets, String additionalParams, String testSelectors) {
    return getBuckTestConfigSettings(
        runManager,
        "Buck " + BuckCommand.TEST.name() + " " + targets,
        targets,
        additionalParams,
        testSelectors);
  }

  private static RunnerAndConfigurationSettings createAndSetBaseConfiguration(
      ConfigurationType type,
      String name,
      RunManager runManager,
      @Nullable String targets,
      @Nullable String additionalParams,
      @Nullable String buckExecutablePath) {
    RunnerAndConfigurationSettings runnerAndConfigurationSettings =
        runManager.createConfiguration(name, type.getConfigurationFactories()[0]);
    // The configuration should always extend AbstractConfiguration
    AbstractConfiguration configuration =
        (AbstractConfiguration) runnerAndConfigurationSettings.getConfiguration();
    if (targets != null) {
      configuration.data.targets = targets;
    }
    if (additionalParams != null) {
      configuration.data.additionalParams = additionalParams;
    }
    if (buckExecutablePath != null) {
      configuration.data.buckExecutablePath = buckExecutablePath;
    }
    return runnerAndConfigurationSettings;
  }
}
