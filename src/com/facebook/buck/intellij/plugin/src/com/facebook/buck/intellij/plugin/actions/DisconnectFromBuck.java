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

import com.facebook.buck.intellij.plugin.config.BuckModule;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

public class DisconnectFromBuck extends DumbAwareAction {
    public static final String ACTION_TITLE = "Disconnect from buck";
    public static final String ACTION_DESCRIPTION = "Disconnect from buck";

    public DisconnectFromBuck() {
        super(ACTION_TITLE, ACTION_DESCRIPTION, AllIcons.Actions.Close);
    }

    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            BuckModule mod = project.getComponent(BuckModule.class);
            e.getPresentation().setVisible(mod.isConnected());
        } else {
            e.getPresentation().setVisible(false);
        }

    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        BuckModule mod = e.getProject().getComponent(BuckModule.class);
        if (mod.isConnected()) {
            mod.disconnect();
        }
    }
}
