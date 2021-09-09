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

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BuckTestConfiguration extends AbstractConfiguration<BuckTestConfiguration.TestData> {

  protected BuckTestConfiguration(
      Project project, @NotNull ConfigurationFactory factory, String name) {
    super(project, factory, name);
  }

  @Override
  protected String getNamePrefix() {
    return "Buck test ";
  }

  @NotNull
  @Override
  public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new BuckTestConfigurationEditor(getProject());
  }

  @Nullable
  @Override
  public RunProfileState getState(
      @NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
    return isBuckBuilding() ? null : new BuckTestExecutionState(this, getProject());
  }

  @Override
  protected TestData createData() {
    return new TestData();
  }

  @Override
  public boolean canRun(ProgramRunner<?> programRunner, String executorId) {
    return programRunner instanceof BuckProgramRunner;
  }

  public static class TestData extends AbstractConfiguration.Data {
    public String testSelectors;

    public TestData() {
      super();
      testSelectors = "";
    }

    private TestData(Data base, String testSelectors) {
      super(base);
      this.testSelectors = testSelectors;
    }

    @Override
    public Object clone() {
      return new TestData((Data) super.clone(), testSelectors);
    }
  }
}
