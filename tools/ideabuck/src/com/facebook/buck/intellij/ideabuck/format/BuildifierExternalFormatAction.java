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

package com.facebook.buck.intellij.ideabuck.format;

import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.facebook.buck.intellij.ideabuck.lang.BuckFileType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Action to invoke {@code buildifier} on the current selection. */
public class BuildifierExternalFormatAction extends DumbAwareAction {

  private static final Logger LOGGER = Logger.getInstance(BuildifierExternalFormatAction.class);

  public BuildifierExternalFormatAction() {
    super("Reformat using buildifier");
  }

  @Override
  public void update(@NotNull AnActionEvent anActionEvent) {
    Presentation presentation = anActionEvent.getPresentation();
    Runnable action = selectAction(anActionEvent);
    presentation.setEnabledAndVisible(action != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
    Runnable action = selectAction(anActionEvent);
    if (action != null) {
      action.run();
    } else {
      LOGGER.warn("No action to perform.");
    }
  }

  @Nullable
  private Runnable selectAction(@NotNull AnActionEvent anActionEvent) {
    Project project = anActionEvent.getProject();
    if (project == null || project.isDefault()) {
      return null;
    }
    BuckExecutableSettingsProvider executableSettings =
        BuckExecutableSettingsProvider.getInstance(project);
    String buildifierExecutable = executableSettings.resolveBuildifierExecutable();
    if (buildifierExecutable == null) {
      return null;
    }
    DataContext dataContext = anActionEvent.getDataContext();
    Editor editor = dataContext.getData(CommonDataKeys.EDITOR);
    if (editor == null) {
      return null;
    }
    Document document = editor.getDocument();
    PsiFile psiFile = dataContext.getData(CommonDataKeys.PSI_FILE);
    if (psiFile == null) {
      psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
    }
    if (psiFile == null) {
      return null;
    }
    if (!BuckFileType.INSTANCE.equals(psiFile.getFileType())) {
      return null;
    }
    return () -> {
      Runnable runnable = () -> BuildifierUtil.doReformat(project, document);
      CommandProcessor.getInstance()
          .executeCommand(
              project,
              runnable,
              null,
              null,
              UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION,
              document);
    };
  }
}
