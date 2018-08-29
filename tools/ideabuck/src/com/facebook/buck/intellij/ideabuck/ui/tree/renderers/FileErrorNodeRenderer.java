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

package com.facebook.buck.intellij.ideabuck.ui.tree.renderers;

import com.facebook.buck.intellij.ideabuck.ui.tree.BuckFileErrorNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode;
import com.intellij.icons.AllIcons;
import com.intellij.ui.components.JBLabel;
import java.awt.Color;
import java.awt.Component;
import javax.swing.SwingConstants;

public class FileErrorNodeRenderer implements TreeNodeRenderer {
  @Override
  public Component render(Object value) {

    JBLabel result =
        new JBLabel(
            ((BuckFileErrorNode) value).getText(),
            AllIcons.Ide.Warning_notifications,
            SwingConstants.HORIZONTAL);

    BuckFileErrorNode buckNode = (BuckFileErrorNode) value;
    for (int i = 0; i < buckNode.getChildCount(); i++) {
      BuckTextNode childNode = (BuckTextNode) buckNode.getChildAt(i);
      if (childNode.getTextType() == BuckTextNode.TextType.ERROR) {
        result.setIcon(AllIcons.Ide.Error);
        result.setForeground(Color.RED);
        break;
      }
    }
    return result;
  }
}
