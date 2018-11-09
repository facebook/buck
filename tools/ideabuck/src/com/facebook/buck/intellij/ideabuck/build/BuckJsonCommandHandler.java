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
package com.facebook.buck.intellij.ideabuck.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.Nullable;

/** Handler for Buck commands that return json objects on stdout. */
public class BuckJsonCommandHandler extends BuckCommandHandler {

  private final Callback mCallback;
  private final StringBuilder mStdoutBuilder;
  private final StringBuilder mStderrBuilder;
  @Nullable private Integer mExitCode;
  @Nullable private Throwable mThrowable;

  /** Command callback invoked post-command. */
  public interface Callback {
    /**
     * If the cmd returned with exit code 0 *and* the result of stdout was parsable as a {@link
     * JsonElement}, this method is invoked with the parsed stdout and raw stderr.
     */
    void onSuccess(JsonElement stdout, String stderr);
    /**
     * If the query failed or the contents of stdout was not parsable as a {@link JsonElement}, this
     * method is invoked with raw stdout/stderr. If the process terminated, include its exit code,
     * and if an exception occurred (either during or after running, such as if the
     */
    void onFailure(
        String stdout, String stderr, @Nullable Integer exitCode, @Nullable Throwable throwable);
  }

  public BuckJsonCommandHandler(Project project, BuckCommand command, Callback callback) {
    super(project, command, true);
    mCallback = callback;
    mStdoutBuilder = new StringBuilder();
    mStderrBuilder = new StringBuilder();
  }

  @Override
  protected void onTextAvailable(String text, Key outputType) {
    if (ProcessOutputTypes.STDOUT.equals(outputType)) {
      mStdoutBuilder.append(text);
    } else if (ProcessOutputTypes.STDERR.equals(outputType)) {
      mStderrBuilder.append(text);
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
    String stdout = mStdoutBuilder.toString();
    String stderr = mStderrBuilder.toString();
    JsonElement jsonElement = null;
    if (mExitCode != null && mExitCode == 0) {
      JsonParser jsonParser = new JsonParser();
      try {
        jsonElement = jsonParser.parse(stdout);
      } catch (JsonSyntaxException e) {
        mThrowable = e;
      }
    }
    if (jsonElement != null) {
      mCallback.onSuccess(jsonElement, stderr);
    } else {
      mCallback.onFailure(stdout, stderr, mExitCode, mThrowable);
    }
  }
}
