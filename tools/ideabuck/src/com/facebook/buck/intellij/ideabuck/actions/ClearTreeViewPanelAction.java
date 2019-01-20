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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.ui.BuckUIManager;
import com.intellij.icons.AllIcons.Actions;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

public class ClearTreeViewPanelAction extends DumbAwareAction {

  public ClearTreeViewPanelAction() {
    final String message = "Clear all";
    getTemplatePresentation().setDescription(message);
    getTemplatePresentation().setText(message);
    getTemplatePresentation().setIcon(Actions.GC);
  }

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      e.getPresentation()
          .setEnabled(
              !BuckBuildManager.getInstance(project).isBuilding()
                  && project.getComponent(BuckModule.class).isConnected());
    }
  }

  @Override
  public void actionPerformed(AnActionEvent anActionEvent) {
    Project project = anActionEvent.getProject();
    if (project != null) {
      BuckUIManager buckUIManager = BuckUIManager.getInstance(project);
      buckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .removeAllChildren(buckUIManager.getBuckTreeViewPanel().getRoot());
    }
  }
}
