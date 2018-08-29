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

import com.facebook.buck.intellij.ideabuck.ui.tree.BuckErrorItemNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckFileErrorNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode;
import com.google.common.collect.ImmutableMap;
import com.intellij.ui.components.JBLabel;
import java.awt.Component;
import javax.swing.JTree;
import javax.swing.tree.TreeCellRenderer;

public class BuckTreeCellRenderer implements TreeCellRenderer {

  private ImmutableMap<Class<?>, TreeNodeRenderer> mRenderers;

  public BuckTreeCellRenderer() {
    mRenderers =
        new ImmutableMap.Builder<Class<?>, TreeNodeRenderer>()
            .put(BuckTextNode.class, new TextNodeRenderer())
            .put(BuckFileErrorNode.class, new FileErrorNodeRenderer())
            .put(BuckErrorItemNode.class, new TextNodeRenderer())
            .build();
  }

  @Override
  public Component getTreeCellRendererComponent(
      JTree tree,
      Object value,
      boolean selected,
      boolean expanded,
      boolean leaf,
      int row,
      boolean hasFocus) {

    Class<?> cc = value.getClass();
    if (mRenderers.containsKey(cc)) {
      TreeNodeRenderer renderer = mRenderers.get(value.getClass());
      return renderer.render(value);
    }
    return new JBLabel("unknown kind of element");
  }
}
