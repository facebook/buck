/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class InstrumentationStep extends ShellStep {

  private final ProjectFilesystem filesystem;
  private final ImmutableList<String> javaRuntimeLauncher;
  private final AndroidInstrumentationTestJVMArgs jvmArgs;

  private Optional<Long> testRuleTimeoutMs;

  public InstrumentationStep(
      ProjectFilesystem filesystem,
      ImmutableList<String> javaRuntimeLauncher,
      AndroidInstrumentationTestJVMArgs jvmArgs,
      Optional<Long> testRuleTimeoutMs) {
    super(filesystem.getRootPath());
    this.filesystem = filesystem;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.jvmArgs = jvmArgs;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(javaRuntimeLauncher);

    jvmArgs.formatCommandLineArgsToList(filesystem, args);

    if (jvmArgs.isDebugEnabled()) {
      warnUser(context, "Debugging. Suspending JVM. Connect android debugger to proceed.");
    }

    return args.build();
  }

  @Override
  public String getShortName() {
    return "instrumentation test";
  }

  @Override
  protected Optional<Long> getTimeout() {
    return testRuleTimeoutMs;
  }

  private void warnUser(ExecutionContext context, String message) {
    context.getStdErr().println(context.getAnsi().asWarningText(message));
  }
}
