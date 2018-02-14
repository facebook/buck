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

import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTreeNodeDetail;
import com.google.common.html.HtmlEscapers;
import com.intellij.icons.AllIcons;
import com.intellij.ui.components.JBLabel;
import java.awt.Component;
import javax.swing.Icon;
import javax.swing.SwingConstants;

public class DetailNodeRenderer implements BuildElementRenderer {
  @Override
  public Component render(Object value) {
    BuckTreeNodeDetail node = (BuckTreeNodeDetail) value;

    Icon icon = AllIcons.Ide.Info_notifications;
    if (node.getType() == BuckTreeNodeDetail.DetailType.ERROR) {
      icon = AllIcons.Ide.Error;
    } else if (node.getType() == BuckTreeNodeDetail.DetailType.WARNING) {
      icon = AllIcons.Ide.Warning_notifications;
    }

    String message =
        "<html><pre style='margin:0px'>"
            + HtmlEscapers.htmlEscaper().escape(node.getDetail())
            + "</pre></html>";

    JBLabel result = new JBLabel(message, icon, SwingConstants.HORIZONTAL);

    result.setToolTipText("<pre>" + node.getDetail() + "</pre>");

    return result;
  }
}
