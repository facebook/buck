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

import com.facebook.buck.intellij.ideabuck.build.BuckAuditCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.file.LightVirtualFileWithDiskFile;
import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.facebook.buck.intellij.ideabuck.logging.EventLogger;
import com.facebook.buck.intellij.ideabuck.logging.EventLoggerFactoryProvider;
import com.facebook.buck.intellij.ideabuck.logging.Keys;
import com.facebook.buck.intellij.ideabuck.util.BuckActionUtils;
import com.google.common.collect.ImmutableMap;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import org.jetbrains.annotations.NotNull;

/** Action to expand the current file as a Buck macro. */
public class BuckExpandMacroAction extends AnAction {

  public static final String ACTION_TITLE = "Expanding Buck macros";

  @Override
  public void update(AnActionEvent e) {
    e.getPresentation()
        .setEnabledAndVisible(BuckActionUtils.findBuckFileFromActionEvent(e) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      return;
    }
    EventLogger buckEventLogger =
        EventLoggerFactoryProvider.getInstance()
            .getBuckEventLogger(Keys.MENU_ITEM)
            .withEventAction(this.getClass().getSimpleName());
    VirtualFile buckFile = BuckActionUtils.findBuckFileFromActionEvent(e);
    if (buckFile == null) {
      return;
    }
    buckEventLogger.withProjectFiles(project, buckFile);
    String path = buckFile.getPath();
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            () -> {
              BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
              BuckAuditCommandHandler commandHandler =
                  new BuckAuditCommandHandler(
                      project,
                      BuckCommand.AUDIT,
                      output ->
                          ApplicationManager.getApplication()
                              .invokeLater(
                                  () -> onResult(output, project, buckFile, buckEventLogger)),
                      () -> onFailure(buckEventLogger));
              commandHandler.command().addParameters("rules", path);
              buildManager.runBuckCommand(commandHandler, ACTION_TITLE);
            });
  }

  private void onResult(
      @NotNull String result,
      @NotNull Project project,
      @NotNull VirtualFile originalFile,
      @NotNull EventLogger buckEventLogger) {
    LightVirtualFileWithDiskFile virtualFile =
        new LightVirtualFileWithDiskFile(
            originalFile.getName(), BuckLanguage.INSTANCE, result, originalFile);
    virtualFile.setWritable(false);
    FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
    fileEditorManager.openFile(virtualFile, true);
    FileEditor editor = fileEditorManager.getSelectedEditor(virtualFile);
    if (editor != null) {
      EditorNotificationPanel panel = new EditorNotificationPanel();
      panel.setText("This file is a read-only file with expanded Buck macros");
      panel.createActionLabel(
          "Open original Buck file", () -> fileEditorManager.openFile(originalFile, true));
      FileEditorManager.getInstance(project).addTopComponent(editor, panel);
    }
    buckEventLogger.log();
  }

  private void onFailure(@NotNull EventLogger buckEventLogger) {
    buckEventLogger
        .withExtraData(ImmutableMap.of(Keys.ERROR, "Failed to complete Buck audit rules"))
        .log();
  }
}
