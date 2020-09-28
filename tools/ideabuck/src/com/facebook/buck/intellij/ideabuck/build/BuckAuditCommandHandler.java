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

package com.facebook.buck.intellij.ideabuck.build;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** Handler for Buck audit command, returns result through a String in a callback. */
public class BuckAuditCommandHandler extends BuckCommandHandler {

  private final Consumer<String> mOnSuccess;
  private final Runnable mOnFailure;
  private final StringBuilder mStdoutBuilder;
  @Nullable private Integer mExitCode;

  public BuckAuditCommandHandler(
      Project project, BuckCommand command, Consumer<String> onSuccess, Runnable onFailure) {
    super(project, command, true);
    mOnSuccess = onSuccess;
    mOnFailure = onFailure;
    mStdoutBuilder = new StringBuilder();
  }

  @Override
  protected void onTextAvailable(String text, Key outputType) {
    if (ProcessOutputTypes.STDOUT.equals(outputType)) {
      mStdoutBuilder.append(text);
    }
  }

  @Override
  protected void processTerminated(ProcessEvent event) {
    mExitCode = event.getExitCode();
    super.processTerminated(event);
  }

  @Override
  protected boolean beforeCommand() {
    return true;
  }

  @Override
  protected void afterCommand() {
    if (mExitCode != null && mExitCode == 0) {
      String stdout = mStdoutBuilder.toString();
      mOnSuccess.accept(stdout);
    } else {
      mOnFailure.run();
    }
  }
}
