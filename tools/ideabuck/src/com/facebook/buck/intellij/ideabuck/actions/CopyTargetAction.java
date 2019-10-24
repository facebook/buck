/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.awt.datatransfer.StringSelection;
import javax.annotation.Nullable;

/**
 * Copy the Buck target of the current file, this currently only works for Java and resource files
 */
public class CopyTargetAction extends DumbAwareAction {

  @Override
  public void update(AnActionEvent e) {
    final Project project = e.getProject();
    final VirtualFile buckFile = findBuckFile(project, e.getData(CommonDataKeys.VIRTUAL_FILE));
    e.getPresentation().setEnabledAndVisible(buckFile != null);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) {
      return;
    }
    final VirtualFile buckFile = findBuckFile(project, e.getData(CommonDataKeys.VIRTUAL_FILE));
    if (buckFile == null) {
      return;
    }
    final BuckTargetPattern buckTargetPattern =
        BuckTargetLocator.getInstance(project)
            .findTargetPatternForVirtualFile(buckFile)
            .orElse(null);
    if (buckTargetPattern == null) {
      return;
    }
    final BuckCellManager.Cell buckCell =
        BuckCellManager.getInstance(project).findCellByVirtualFile(buckFile).orElse(null);
    if (buckCell == null) {
      return;
    }
    final String cellPath = buckTargetPattern.getCellPath().orElse(null);
    if (cellPath == null) {
      return;
    }
    final String[] parts = cellPath.split("/");
    if (parts.length == 0) {
      return;
    }

    CopyPasteManager.getInstance()
        .setContents(new StringSelection("//" + cellPath + ":" + parts[parts.length - 1]));
  }

  private @Nullable VirtualFile findBuckFile(Project project, @Nullable VirtualFile currentFile) {
    if (currentFile == null) {
      return null;
    }
    VirtualFile buckFile =
        BuckTargetLocator.getInstance(project).findBuckFileForVirtualFile(currentFile).orElse(null);

    return buckFile == null || buckFile.equals(currentFile) ? null : buckFile;
  }
}
