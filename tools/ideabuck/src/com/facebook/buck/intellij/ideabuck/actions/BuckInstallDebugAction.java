/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import javax.swing.Icon;

public class BuckInstallDebugAction extends BuckInstallAction {
  public static final String ACTION_TITLE = "Run buck install and debug";
  public static final String ACTION_DESCRIPTION = "Run buck install command and debug";
  public static final Icon ICON = BuckIcons.ACTION_DEBUG;

  private static boolean mDebug;

  public BuckInstallDebugAction() {
    super(ACTION_TITLE, ACTION_DESCRIPTION, ICON);
  }

  public static synchronized void setDebug(boolean debug) {
    mDebug = debug;
  }

  public static synchronized boolean shouldDebug() {
    return mDebug;
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    setDebug(true);
    super.actionPerformed(e);
  }
}
