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

package com.facebook.buck.intellij.ideabuck.test.util;

import com.facebook.buck.intellij.ideabuck.ui.BuckUIManager;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckToolWindow;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckTreeViewPanel;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckTreeViewPanelImpl.ModifiableModelImpl;
import com.facebook.buck.intellij.ideabuck.ui.components.ModifiableModel;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode.TextType;
import com.intellij.openapi.project.Project;
import com.intellij.ui.treeStructure.Tree;
import javax.swing.JComponent;
import javax.swing.tree.DefaultTreeModel;
import org.easymock.EasyMock;

public class MockBuckUIManager extends BuckUIManager {

  private BuckToolWindow mBuckToolWindow;
  private BuckTreeViewPanel mBuckTreeViewPanel;

  public MockBuckUIManager(Project project) {
    super(project);
    mBuckToolWindow = EasyMock.createMock(BuckToolWindow.class);
    mBuckTreeViewPanel = new MockBuckTreeViewPanel();
  }

  @Override
  public BuckToolWindow getBuckToolWindow() {
    return mBuckToolWindow;
  }

  @Override
  public BuckTreeViewPanel getBuckTreeViewPanel() {
    return mBuckTreeViewPanel;
  }

  private static class MockBuckTreeViewPanel implements BuckTreeViewPanel {
    private BuckTextNode mRoot;
    private ModifiableModel mModifiableModel;

    public MockBuckTreeViewPanel() {
      mRoot = new BuckTextNode("", TextType.INFO);
      DefaultTreeModel treeModel = new DefaultTreeModel(mRoot);
      mModifiableModel = new ModifiableModelImpl(treeModel, EasyMock.createMock(Tree.class));
    }

    @Override
    public BuckTextNode getRoot() {
      return mRoot;
    }

    @Override
    public ModifiableModel getModifiableModel() {
      return mModifiableModel;
    }

    @Override
    public String getId() {
      return null;
    }

    @Override
    public String getTitle() {
      return null;
    }

    @Override
    public JComponent getComponent() {
      return null;
    }
  }
}
