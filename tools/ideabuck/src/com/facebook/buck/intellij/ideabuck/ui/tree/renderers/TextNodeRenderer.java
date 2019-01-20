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

import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode;
import com.google.common.html.HtmlEscapers;
import com.intellij.icons.AllIcons;
import com.intellij.ui.components.JBLabel;
import java.awt.Component;
import javax.swing.*;

public class TextNodeRenderer implements TreeNodeRenderer {
  @Override
  public Component render(Object value) {
    BuckTextNode node = (BuckTextNode) value;

    if (node.getText().isEmpty()) {
      return new JLabel("");
    }

    Icon icon = AllIcons.Ide.Info_notifications;
    if (node.getTextType() == BuckTextNode.TextType.ERROR) {
      icon = AllIcons.Ide.Error;
    } else if (node.getTextType() == BuckTextNode.TextType.WARNING) {
      icon = AllIcons.Ide.Warning_notifications;
    }

    String message =
        "<html><pre style='margin:0px'>"
            + HtmlEscapers.htmlEscaper().escape(node.getText())
            + "</pre></html>";

    JBLabel result = new JBLabel(message, icon, SwingConstants.HORIZONTAL);

    result.setToolTipText("<pre>" + node.getText() + "</pre>");

    return result;
  }
}
