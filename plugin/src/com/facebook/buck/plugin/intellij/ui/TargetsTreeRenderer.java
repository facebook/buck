/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij.ui;

import com.google.common.base.Strings;
import com.intellij.icons.AllIcons;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.MultilineTreeCellRenderer;
import com.intellij.ui.SideBorder;

import javax.swing.Icon;
import javax.swing.JScrollPane;
import javax.swing.JTree;

class TargetsTreeRenderer extends MultilineTreeCellRenderer {

  private TargetsTreeRenderer() {
  }

  public static JScrollPane install(JTree tree) {
    JScrollPane scrollPane = installRenderer(tree, new TargetsTreeRenderer());
    scrollPane.setBorder(IdeBorderFactory.createBorder(SideBorder.LEFT));
    return scrollPane;
  }

  @Override
  protected void initComponent(JTree tree,
                               Object value,
                               boolean selected,
                               boolean expanded,
                               boolean leaf,
                               int row,
                               boolean hasFocus) {
    if (value instanceof TargetNode) {
      TargetNode node = (TargetNode) value;
      String[] text = new String[] {node.getName()};
      Icon icon;
      switch (node.getType()) {
        case DIRECTORY:
          icon = AllIcons.Hierarchy.Base;
          break;
        case JAVA_LIBRARY:
          icon = AllIcons.Modules.Library;
          break;
        case JAVA_BINARY:
          icon = AllIcons.RunConfigurations.Application;
          break;
        case JAVA_TEST:
          icon = AllIcons.RunConfigurations.Junit;
          break;
        case OTHER:
          icon = AllIcons.General.ExternalTools;
          break;
        default:
          icon = AllIcons.General.Error;
      }
      setText(text, "");
      setIcon(icon);
    } else {
      String[] text = new String[] {value == null ? "" : value.toString()};
      text[0] = Strings.nullToEmpty(text[0]);
      setText(text, null);
      setIcon(null);
    }
  }
}
