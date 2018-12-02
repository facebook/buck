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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import java.io.IOException;
import java.lang.reflect.Type;
import org.jetbrains.annotations.Nullable;

/** Handler for Buck commands that return json objects on stdout. */
public class BuckJsonCommandHandler<T> extends BuckCommandHandler {

  private final Callback<T> mCallback;
  private final StringBuilder mStdoutBuilder;
  private final StringBuilder mStderrBuilder;
  @Nullable private Integer mExitCode;
  @Nullable private Throwable mThrowable;

  /** Command callback invoked post-command. */
  public interface Callback<T> {

    /**
     * Deserializes the given JsonElement into a type appropriate for passing to {@link
     * #onSuccess(Object, String)}.
     *
     * <p>By default, this method defaults to {@link Gson} deserialization. Be advised that when
     * working with generic types, it may be necessary to override the default implementation (due
     * to type erasure).
     *
     * @throws IOException if it is not possible to deserialize the given {@link JsonElement}
     * @see <a href="https://github.com/google/gson/blob/master/UserGuide.md">the Gson User Guide
     *     for more info on deserialization</a>
     */
    default T deserialize(JsonElement jsonElement) throws IOException {
      Type type = new TypeToken<T>() {}.getType();
      return new Gson().fromJson(jsonElement, type);
    }

    /**
     * If the cmd returned with exit code 0 *and* the result of stdout was parsable as a {@link
     * JsonElement}, this method is invoked with the parsed stdout and raw stderr.
     */
    void onSuccess(T stdout, String stderr);
    /**
     * If the query failed or the contents of stdout was not parsable as a {@link JsonElement}, this
     * method is invoked with raw stdout/stderr. If the process terminated, include its exit code,
     * and if an exception occurred (either during or after running, such as if the
     */
    void onFailure(
        String stdout, String stderr, @Nullable Integer exitCode, @Nullable Throwable throwable);
  }

  public BuckJsonCommandHandler(Project project, BuckCommand command, Callback<T> callback) {
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
    T result = null;
    if (mExitCode != null && mExitCode == 0) {
      JsonParser jsonParser = new JsonParser();
      try {
        JsonElement jsonElement = jsonParser.parse(stdout);
        result = mCallback.deserialize(jsonElement);
      } catch (IOException e) {
        mThrowable = e;
      }
    }
    if (result != null) {
      mCallback.onSuccess(result, stderr);
    } else {
      mCallback.onFailure(stdout, stderr, mExitCode, mThrowable);
    }
  }
}
