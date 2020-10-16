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

import com.facebook.buck.intellij.ideabuck.endtoend.BuckTestCase;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;

public class BuckRunnerAndConfigurationSettingsFactoryTest extends BuckTestCase {

  public void testBuckBuildConfig() {
    RunManager runManager = RunManager.getInstance(getProject());
    String target = "//foo:bar";
    String params = "";
    RunnerAndConfigurationSettings settings =
        BuckRunnerAndConfigurationSettingsFactory.getBuckBuildConfigSettings(
            runManager, target, params);
    assertInstanceOf(settings.getConfiguration(), BuckBuildConfiguration.class);
    BuckBuildConfiguration configuration = (BuckBuildConfiguration) settings.getConfiguration();
    assertEquals(target, configuration.data.targets);
    assertEquals(params, configuration.data.additionalParams);
    assertEquals("", configuration.data.buckExecutablePath);
  }

  public void testBuckRunConfig() {
    RunManager runManager = RunManager.getInstance(getProject());
    String target = "//foo:bar";
    String params = "";
    RunnerAndConfigurationSettings settings =
        BuckRunnerAndConfigurationSettingsFactory.getBuckRunConfigSettings(
            runManager, target, params);
    assertInstanceOf(settings.getConfiguration(), BuckRunConfiguration.class);
    BuckRunConfiguration configuration = (BuckRunConfiguration) settings.getConfiguration();
    assertEquals(target, configuration.data.targets);
    assertEquals(params, configuration.data.additionalParams);
    assertEquals("", configuration.data.buckExecutablePath);
  }

  public void testBuckTestConfig() {
    RunManager runManager = RunManager.getInstance(getProject());
    String name = "mytest";
    String target = "//foo:bar";
    String params = "";
    String selectors = "bar";
    RunnerAndConfigurationSettings settings =
        BuckRunnerAndConfigurationSettingsFactory.getBuckTestConfigSettings(
            runManager, name, target, params, selectors);
    assertInstanceOf(settings.getConfiguration(), BuckTestConfiguration.class);
    BuckTestConfiguration configuration = (BuckTestConfiguration) settings.getConfiguration();
    assertEquals(name, configuration.getName());
    assertEquals(target, configuration.data.targets);
    assertEquals(params, configuration.data.additionalParams);
    assertEquals("", configuration.data.buckExecutablePath);
    assertEquals(selectors, configuration.data.testSelectors);
  }
}
