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

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import javax.swing.Icon;

abstract class BuckBaseAction extends DumbAwareAction {

  public BuckBaseAction(String title, String desc, Icon icon) {
    super(title, desc, icon);
  }

  public abstract void executeOnPooledThread(final AnActionEvent e);

  @Override
  public void actionPerformed(final AnActionEvent e) {
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            new Runnable() {
              @Override
              public void run() {
                executeOnPooledThread(e);
              }
            });
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
}
