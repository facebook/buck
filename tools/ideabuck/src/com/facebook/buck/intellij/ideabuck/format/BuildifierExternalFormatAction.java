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
package com.facebook.buck.intellij.ideabuck.format;

import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.facebook.buck.intellij.ideabuck.lang.BuckFileType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** Action to invoke {@code buildifier} on the current selection. */
public class BuildifierExternalFormatAction extends DumbAwareAction {

  private static final Logger LOGGER = Logger.getInstance(BuildifierExternalFormatAction.class);

  public BuildifierExternalFormatAction() {
    super("Reformat using buildifier");
  }

  @Override
  public void update(@NotNull AnActionEvent anActionEvent) {
    Presentation presentation = anActionEvent.getPresentation();
    Project project = anActionEvent.getProject();
    if (project == null || project.isDefault()) {
      presentation.setEnabledAndVisible(false);
      return;
    }
    BuckExecutableSettingsProvider executableSettings =
        BuckExecutableSettingsProvider.getInstance(project);
    String buildifierExecutable = executableSettings.resolveBuildifierExecutable();
    if (buildifierExecutable == null) {
      presentation.setEnabledAndVisible(false);
      return;
    }

    DataContext dataContext = anActionEvent.getDataContext();
    PsiFile psiFile = dataContext.getData(CommonDataKeys.PSI_FILE);
    if (psiFile != null && BuckFileType.INSTANCE.equals(psiFile.getFileType())) {
      presentation.setEnabledAndVisible(true);
      return;
    }

    VirtualFile virtualFile = dataContext.getData(CommonDataKeys.VIRTUAL_FILE);
    if (virtualFile != null
        && virtualFile.isWritable()
        && BuckFileType.INSTANCE.equals(virtualFile.getFileType())) {
      presentation.setEnabledAndVisible(true);
      return;
    }
    presentation.setEnabledAndVisible(false);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
    Project project = anActionEvent.getProject();
    if (project == null || project.isDefault()) {
      return;
    }
    DataContext dataContext = anActionEvent.getDataContext();
    Document document = dataContext.getData(CommonDataKeys.EDITOR).getDocument();
    if (document != null) {
      BuildifierUtil.doReformat(project, document);
      return;
    }
    PsiFile psiFile = dataContext.getData(CommonDataKeys.PSI_FILE);
    if (psiFile != null) {
      BuildifierUtil.doReformat(psiFile);
      return;
    }
    VirtualFile virtualFile = dataContext.getData(CommonDataKeys.VIRTUAL_FILE);
    if (virtualFile != null) {
      BuildifierUtil.doReformat(project, virtualFile);
      return;
    }
    LOGGER.warn("Could not perform action.");
  }
}
