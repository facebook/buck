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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.JavaStepsBuilder;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.OutputStream;

/** Executes java compilation command. */
class BuildJavaCommandExecutor {

  private BuildJavaCommandExecutor() {}

  static void executeBuildJavaCommand(
      ClassLoaderCache classLoaderCache,
      String actionId,
      BuildJavaCommand buildJavaCommand,
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol,
      IsolatedEventBus eventBus,
      Platform platform,
      ProcessExecutor processExecutor,
      Console console,
      Clock clock)
      throws IOException {

    JavaStepsBuilder javaStepsBuilder = new JavaStepsBuilder(buildJavaCommand);
    AbsPath ruleCellRoot = javaStepsBuilder.getRuleCellRoot();
    ImmutableList<IsolatedStep> steps = javaStepsBuilder.getSteps();

    StepExecutionUtils.executeSteps(
        classLoaderCache,
        eventBus,
        eventsOutputStream,
        downwardProtocol,
        platform,
        processExecutor,
        console,
        clock,
        actionId,
        ruleCellRoot,
        steps);
  }
}
