/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.DefaultProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TestProgramRunner extends DefaultProgramRunner {

  public static final String ID = "buck.test.program.runner";

  @Nullable
  @Override
  protected RunContentDescriptor doExecute(
      @NotNull RunProfileState state, @NotNull ExecutionEnvironment env) throws ExecutionException {
    checkBuckSettings(env.getProject());
    return super.doExecute(state, env);
  }

  private void checkBuckSettings(Project project) throws ExecutionException {
    String exec = BuckProjectSettingsProvider.getInstance(project).resolveBuckExecutable();
    if (exec == null) {
      throw new ExecutionException(
          "Please specify the buck executable path!\n"
              + "Preference -> Tools -> Buck -> Path to Buck executable");
    }
  }

  @NotNull
  @Override
  public String getRunnerId() {
    return ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return profile instanceof TestConfiguration;
  }
}
