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

import javax.swing.*;

class MessageTreeRenderer extends MultilineTreeCellRenderer {

  private MessageTreeRenderer() {
  }

  public static JScrollPane install(JTree tree) {
    JScrollPane scrollPane = installRenderer(tree, new MessageTreeRenderer());
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
    if (value instanceof ProgressNode) {
      ProgressNode node = (ProgressNode) value;
      String[] text = new String[] {node.getName()};
      String prefix;
      Icon icon;
      switch (node.getType()) {
        case DIRECTORY:
          prefix = "";
          icon = AllIcons.General.Filter;
          break;
        case BUILDING:
          prefix = "Building";
          icon = AllIcons.Process.Step_1;
          break;
        case BUILT:
          prefix = "Built";
          icon = AllIcons.Ant.Message;
          break;
        case BUILT_CACHED:
          prefix = "Built";
          icon = AllIcons.General.Gear;
          break;
        case BUILD_ERROR:
          prefix = "Error";
          icon = AllIcons.General.Error;
          break;
        case TEST_CASE_SUCCESS:
          prefix = "Test";
          icon = AllIcons.Modules.TestRoot;
          break;
        case TEST_CASE_FAILURE:
          prefix = "Test Failure";
          icon = AllIcons.General.Error;
          break;
        case TEST_RESULT_SUCCESS:
          prefix = "";
          icon = AllIcons.Nodes.Advice;
          break;
        case TEST_RESULT_FAILURE:
          prefix = "";
          icon = AllIcons.General.Error;
          break;
        default:
          icon = AllIcons.General.Error;
          prefix = "";
      }
      setText(text, prefix);
      setIcon(icon);
    } else {
      String[] text = new String[] {value == null ? "" : value.toString()};
      text[0] = Strings.nullToEmpty(text[0]);
      setText(text, null);
      setIcon(null);
    }
  }
}
