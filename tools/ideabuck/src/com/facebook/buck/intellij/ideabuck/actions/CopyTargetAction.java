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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.logging.EventLogger;
import com.facebook.buck.intellij.ideabuck.logging.EventLoggerFactoryProvider;
import com.facebook.buck.intellij.ideabuck.logging.Keys;
import com.facebook.buck.intellij.ideabuck.util.BuckActionUtils;
import com.google.common.collect.ImmutableMap;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import java.awt.datatransfer.StringSelection;
import javax.annotation.Nullable;

/**
 * Copy the Buck target of the current file, this currently only works for Java and resource files
 */
public class CopyTargetAction extends AnAction {

  @Override
  public void update(AnActionEvent e) {
    VirtualFile buckFile = BuckActionUtils.findBuckFileFromActionEvent(e);
    if (buckFile != null && buckFile.equals(e.getData(CommonDataKeys.VIRTUAL_FILE))) {
      e.getPresentation().setEnabledAndVisible(BuckActionUtils.isActionEventInRuleBody(e));
    } else {
      e.getPresentation().setEnabledAndVisible(buckFile != null);
    }
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    final Project project = e.getProject();
    EventLogger buckEventLogger =
        EventLoggerFactoryProvider.getInstance()
            .getBuckEventLogger(Keys.MENU_ITEM)
            .withEventAction(this.getClass().getSimpleName());
    if (project == null) {
      fail("The current project is invalid", buckEventLogger);
      return;
    }
    // At this point, we have the project
    buckEventLogger.withProjectFiles(project, e.getData(CommonDataKeys.VIRTUAL_FILE));
    final VirtualFile buckFile = BuckActionUtils.findBuckFileFromActionEvent(e);
    if (buckFile == null) {
      fail("Unable to find a BUCK file", buckEventLogger);
      return;
    }
    final BuckTargetPattern buckTargetPattern =
        BuckTargetLocator.getInstance(project)
            .findTargetPatternForVirtualFile(buckFile)
            .orElse(null);
    if (buckTargetPattern == null) {
      fail(
          "Unable to find a Buck target pattern for " + buckFile.getCanonicalPath(),
          buckEventLogger,
          buckFile);
      return;
    }
    BuckCellManager buckCellManager = BuckCellManager.getInstance(project);
    final BuckCellManager.Cell buckCell =
        buckCellManager.findCellByVirtualFile(buckFile).orElse(null);
    String targetPath = buckTargetPattern.toString();
    if (buckCellManager
            .getDefaultCell()
            .map(defaultCell -> defaultCell.equals(buckCell))
            .orElse(false)
        && targetPath.contains("//")) {
      // If it is in the default cell, remove the cell name prefix
      targetPath = "//" + targetPath.split("//")[1];
    }
    if (buckFile.equals(e.getData(CommonDataKeys.VIRTUAL_FILE))) {
      final String targetName = BuckActionUtils.getTargetNameFromActionEvent(e);
      if (targetName != null) {
        String target = targetPath + targetName;
        CopyPasteManager.getInstance().setContents(new StringSelection(target));
        buckEventLogger
            .withExtraData(
                ImmutableMap.of(Keys.BUCK_FILE, buckFile.getCanonicalPath(), Keys.TARGET, target))
            .log();
      } else {
        fail("Unable to get the name of the Buck target", buckEventLogger, buckFile);
      }
      return;
    }
    final String cellPath = buckTargetPattern.getCellPath().orElse(null);
    if (cellPath == null) {
      fail("Unable to get the cell path of " + buckTargetPattern, buckEventLogger, buckFile);
      return;
    }
    final String[] parts = cellPath.split("/");
    if (parts.length == 0) {
      fail("Cell path " + cellPath + " is invalid", buckEventLogger, buckFile);
      return;
    }
    String target = targetPath + parts[parts.length - 1];
    CopyPasteManager.getInstance().setContents(new StringSelection(target));
    buckEventLogger
        .withExtraData(
            ImmutableMap.of(Keys.BUCK_FILE, buckFile.getCanonicalPath(), Keys.TARGET, target))
        .log();
  }

  private static void fail(String message, EventLogger buckEventLogger) {
    fail(message, buckEventLogger, null);
  }

  private static void fail(
      String message, EventLogger buckEventLogger, @Nullable VirtualFile buckFile) {
    String buckFilePath = buckFile == null ? "null" : buckFile.getCanonicalPath();
    buckEventLogger
        .withExtraData(ImmutableMap.of(Keys.ERROR, message, Keys.BUCK_FILE, buckFilePath))
        .log();
    Messages.showWarningDialog(message, "Failed to Copy Buck Target");
  }
}
