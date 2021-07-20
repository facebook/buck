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

package com.facebook.tools.intellij.ideabuck.build;

import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckJsonCommandHandler;
import com.google.gson.JsonElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.jetbrains.annotations.Nullable;

/** Runner util class to run buck from other plugin where some library calls cause exception */
public class BuckCommandRunnerUtils {
  private BuckCommandRunnerUtils() {}

  /** Runner for buck query owner(path) that returns a list of buck paths */
  public static void runQueryOwner(
      Project project,
      String filepath,
      Consumer<List<String>> onSuccessCallback,
      BiConsumer<String, String> onFailureCallback) {
    BuckJsonCommandHandler<List<String>> handler =
        new BuckJsonCommandHandler<>(
            project,
            BuckCommand.QUERY,
            new BuckQueryOwnerStringListCommandHandlerCallback(
                filepath, onSuccessCallback, onFailureCallback));
    handler.command().addParameters("owner(%s)", filepath);
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            () -> {
              handler.runInCurrentThread(null);
            });
  }

  private static class BuckQueryOwnerStringListCommandHandlerCallback
      implements BuckJsonCommandHandler.Callback<List<String>> {
    private final String mFilepath;
    private final Consumer<List<String>> mOnSuccessCallback;
    private final BiConsumer<String, String> mOnFailureCallback;

    BuckQueryOwnerStringListCommandHandlerCallback(
        String filepath,
        Consumer<List<String>> onSuccessCallback,
        BiConsumer<String, String> onFailureCallback) {
      mFilepath = filepath;
      mOnSuccessCallback = onSuccessCallback;
      mOnFailureCallback = onFailureCallback;
    }

    @Override
    public List<String> deserialize(JsonElement jsonElement) throws IOException {
      if (!jsonElement.isJsonObject()) {
        return Collections.emptyList();
      }
      JsonElement buckPathsElement = jsonElement.getAsJsonObject().get(mFilepath);
      if (buckPathsElement == null || !buckPathsElement.isJsonArray()) {
        return Collections.emptyList();
      }
      return StreamSupport.stream(buckPathsElement.getAsJsonArray().spliterator(), false)
          .map(JsonElement::getAsString)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }

    @Override
    public void onSuccess(List<String> buckPaths, String stdout) {
      mOnSuccessCallback.accept(buckPaths);
    }

    @Override
    public void onFailure(
        String stdout, String stderr, @Nullable Integer integer, @Nullable Throwable throwable) {
      mOnFailureCallback.accept(stdout, stderr);
    }
  }
}
