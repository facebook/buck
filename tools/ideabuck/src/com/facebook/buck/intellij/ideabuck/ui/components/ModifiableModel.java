/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.ui.components;

import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode;
import com.intellij.ui.treeStructure.Tree;
import java.util.List;
import java.util.function.Function;
import javax.swing.event.TreeModelListener;

public interface ModifiableModel {
  Tree getTree();

  void addTreeModelListener(Function<Tree, TreeModelListener> treeModelListenerFunction);

  void removeTreeModelListener(TreeModelListener treeModelListener);

  void addDefaultTreeModelListener();

  void removeDefaultTreeModelListener();

  void addChild(BuckTextNode parent, BuckTextNode child, boolean syncAfter);

  void addChild(BuckTextNode parent, BuckTextNode child);

  void addChildren(BuckTextNode parent, List<BuckTextNode> children, boolean syncAfter);

  void addChildren(BuckTextNode parent, List<BuckTextNode> children);

  void setNodeText(
      BuckTextNode node, String text, BuckTextNode.TextType textType, boolean syncAfter);

  void setNodeText(BuckTextNode node, String text, BuckTextNode.TextType textType);

  void setNodeText(BuckTextNode node, String text, boolean syncAfter);

  void setNodeText(BuckTextNode node, String text);

  void removeAllChildren(BuckTextNode node, boolean syncAfter);

  void removeAllChildren(BuckTextNode node);

  boolean shouldScrollToEnd();

  void setScrollToEnd(boolean scrollToEnd);

  void scrollToEnd();
}
