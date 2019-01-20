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

import com.facebook.buck.intellij.ideabuck.ui.BuckUIManager;
import com.intellij.icons.AllIcons;
import com.intellij.idea.ActionsBundle;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;

public class ScrollToEndAction extends ToggleAction implements DumbAware {

  public ScrollToEndAction() {
    final String message = ActionsBundle.message("action.EditorConsoleScrollToTheEnd.text");
    getTemplatePresentation().setDescription(message);
    getTemplatePresentation().setText(message);
    getTemplatePresentation().setIcon(AllIcons.RunConfigurations.Scroll_down);
  }

  @Override
  public boolean isSelected(AnActionEvent anActionEvent) {
    Project project = anActionEvent.getProject();
    return project != null
        && BuckUIManager.getInstance(project)
            .getBuckTreeViewPanel()
            .getModifiableModel()
            .shouldScrollToEnd();
  }

  @Override
  public void setSelected(AnActionEvent anActionEvent, boolean b) {
    Project project = anActionEvent.getProject();
    if (project != null) {
      BuckUIManager buckUIManager = BuckUIManager.getInstance(project);
      buckUIManager.getBuckTreeViewPanel().getModifiableModel().setScrollToEnd(b);
      if (b) {
        buckUIManager.getBuckTreeViewPanel().getModifiableModel().scrollToEnd();
      }
    }
  }
}
