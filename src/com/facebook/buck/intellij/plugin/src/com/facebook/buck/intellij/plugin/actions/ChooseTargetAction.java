/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.plugin.actions;

import com.facebook.buck.intellij.plugin.actions.choosetargets.ChooseTargetItem;
import com.facebook.buck.intellij.plugin.actions.choosetargets.ChooseTargetModel;
import com.facebook.buck.intellij.plugin.config.BuckSettingsProvider;
import com.facebook.buck.intellij.plugin.ui.BuckToolWindowFactory;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.GotoActionBase;
import com.intellij.ide.util.gotoByName.ChooseByNamePopup;
import com.intellij.ide.util.gotoByName.DefaultChooseByNameItemProvider;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;

/**
 * Pop up a GUI for choose buck targets (alias).
 */
public class ChooseTargetAction extends GotoActionBase implements DumbAware {

  public static final String ACTION_TITLE = "Choose build target";
  public static final String ACTION_DESCRIPTION = "Choose Buck build target or alias";

  public ChooseTargetAction() {
    Presentation presentation = this.getTemplatePresentation();
    presentation.setText(ACTION_TITLE);
    presentation.setDescription(ACTION_DESCRIPTION);
    presentation.setIcon(AllIcons.Actions.Preview);
  }

  @Override
  protected void gotoActionPerformed(AnActionEvent e) {
    final Project project = e.getData(CommonDataKeys.PROJECT);
    if (project == null) {
      return;
    }

    final ChooseTargetModel model = new ChooseTargetModel(project);
    GotoActionCallback<String> callback = new GotoActionCallback<String>() {
      @Override
      public void elementChosen(ChooseByNamePopup chooseByNamePopup, Object element) {
        if (element == null) {
          return;
        }

        BuckSettingsProvider buckSettingsProvider = BuckSettingsProvider.getInstance();
        if (buckSettingsProvider == null || buckSettingsProvider.getState() == null) {
          return;
        }

        ChooseTargetItem item = (ChooseTargetItem) element;
        if (buckSettingsProvider.getState().lastAlias != null) {
          buckSettingsProvider.getState().lastAlias.put(
              project.getBasePath(), item.getBuildTarget());
        }
        BuckToolWindowFactory.updateBuckToolWindowTitle(project);
      }
    };

    DefaultChooseByNameItemProvider provider =
        new DefaultChooseByNameItemProvider(getPsiContext(e));
    showNavigationPopup(e, model, callback, "Choose Build Target", true, false, provider);
  }
}
