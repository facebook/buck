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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.actions.choosetargets.ChooseTargetItem;
import com.facebook.buck.intellij.ideabuck.actions.choosetargets.ChooseTargetModel;
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.facebook.buck.intellij.ideabuck.ui.BuckUIManager;
import com.intellij.ide.actions.GotoActionBase;
import com.intellij.ide.util.gotoByName.ChooseByNamePopup;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;

/** Pop up a GUI for choose buck targets (alias). */
public class ChooseTargetAction extends GotoActionBase implements DumbAware {

  public static final String ACTION_TITLE = "Choose build target";
  public static final String ACTION_DESCRIPTION = "Choose Buck build target or alias";

  public ChooseTargetAction() {
    Presentation presentation = this.getTemplatePresentation();
    presentation.setText(ACTION_TITLE);
    presentation.setDescription(ACTION_DESCRIPTION);
    presentation.setIcon(BuckIcons.ACTION_FIND);
  }

  @Override
  public void update(AnActionEvent e) {
    e.getPresentation().setEnabled(!(e.getProject() == null || DumbService.isDumb(e.getProject())));
  }

  @Override
  protected void gotoActionPerformed(AnActionEvent e) {
    final Project project = e.getData(CommonDataKeys.PROJECT);
    if (project == null) {
      return;
    }

    final ChooseTargetModel model = new ChooseTargetModel(project);
    GotoActionCallback<String> callback =
        new GotoActionCallback<String>() {
          @Override
          public void elementChosen(ChooseByNamePopup chooseByNamePopup, Object element) {
            if (element == null) {
              return;
            }

            ChooseTargetItem item = (ChooseTargetItem) element;
            // if the target selected isn't an alias, then it has to have : or end with /...
            if (item.getName().contains("//")
                && !item.getName().contains(":")
                && !item.getName().endsWith("/...")) {
              return;
            }

            BuckProjectSettingsProvider buckProjectSettingsProvider =
                BuckProjectSettingsProvider.getInstance(project);
            buckProjectSettingsProvider.setLastAlias(item.getBuildTarget());
            BuckUIManager buckUIManager = BuckUIManager.getInstance(project);
            buckUIManager.getBuckToolWindow().updateMainToolWindowTitleByTarget();
          }
        };
    showNavigationPopup(e, model, callback, "Choose Build Target", true, false);

    // Add navigation listener for auto complete
    final ChooseByNamePopup chooseByNamePopup =
        project.getUserData(ChooseByNamePopup.CHOOSE_BY_NAME_POPUP_IN_PROJECT_KEY);
    chooseByNamePopup
        .getTextField()
        .addKeyListener(
            new KeyAdapter() {
              @Override
              public void keyPressed(KeyEvent e) {
                if (KeyEvent.VK_RIGHT == e.getKeyCode()) {
                  ChooseTargetItem obj = (ChooseTargetItem) chooseByNamePopup.getChosenElement();
                  if (obj != null) {
                    chooseByNamePopup.getTextField().setText(obj.getName());
                    chooseByNamePopup.getTextField().repaint();
                  }
                } else {
                  super.keyPressed(e);
                }
                String adText = chooseByNamePopup.getAdText();
                if (adText != null) {
                  chooseByNamePopup.setAdText(
                      adText
                          + " and "
                          + KeymapUtil.getKeystrokeText(
                              KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 2))
                          + " to use autocomplete");
                }
              }
            });
  }
}
