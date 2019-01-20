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

package com.facebook.buck.intellij.ideabuck.build;

import com.google.common.base.Function;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.LinkedList;
import java.util.List;

public class BuckQueryCommandHandler extends BuckCommandHandler {

  private Function<List<String>, Void> actionsToExecute;

  /** @deprecated Use {@link BuckQueryCommandHandler(Project, BuckCommand, Function)}. */
  @Deprecated
  public BuckQueryCommandHandler(
      final Project project,
      final VirtualFile root,
      final BuckCommand command,
      Function<List<String>, Void> actionsToExecute) {
    super(project, VfsUtil.virtualToIoFile(root), command, true);
    this.actionsToExecute = actionsToExecute;
  }

  public BuckQueryCommandHandler(
      Project project, BuckCommand command, Function<List<String>, Void> actionsToExecute) {
    super(project, command, true);
    this.actionsToExecute = actionsToExecute;
  }

  @Override
  protected void notifyLines(Key outputType, Iterable<String> lines) {
    super.notifyLines(outputType, lines);
    if (outputType == ProcessOutputTypes.STDOUT) {
      List<String> targetList = new LinkedList<String>();
      for (String outputLine : lines) {
        if (!outputLine.isEmpty()) {
          JsonElement jsonElement = new JsonParser().parse(outputLine);
          if (jsonElement.isJsonArray()) {
            JsonArray targets = jsonElement.getAsJsonArray();

            for (JsonElement target : targets) {
              targetList.add(target.getAsString());
            }
          }
        }
      }
      actionsToExecute.apply(targetList);
    }
  }

  @Override
  protected boolean beforeCommand() {
    return true;
  }

  @Override
  protected void afterCommand() {}
}
