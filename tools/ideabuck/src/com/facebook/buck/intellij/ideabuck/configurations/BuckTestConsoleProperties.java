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
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.testframework.JavaTestLocator;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing;
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter;
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties;
import com.intellij.execution.testframework.sm.runner.SMTestLocator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BuckTestConsoleProperties extends SMTRunnerConsoleProperties
    implements SMCustomMessagesParsing {

  private final ProcessHandler mHandler;

  public BuckTestConsoleProperties(
      ProcessHandler handler,
      Project project,
      RunProfile runConfiguration,
      String frameworkName,
      Executor executor) {
    super(project, runConfiguration, frameworkName, executor);
    mHandler = handler;
  }

  @Override
  public OutputToGeneralTestEventsConverter createTestEventsConverter(
      @NotNull String testFrameworkName, @NotNull TestConsoleProperties consoleProperties) {
    return new BuckToGeneralTestEventsConverter(
        testFrameworkName, consoleProperties, mHandler, getProject());
  }

  @Nullable
  @Override
  public SMTestLocator getTestLocator() {
    return new JavaTestLocator();
  }
}
