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

import com.facebook.buck.plugin.intellij.BuckPluginComponent;
import com.facebook.buck.plugin.intellij.BuckTarget;
import com.google.common.base.Preconditions;
import com.intellij.ui.treeStructure.Tree;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.event.MouseInputAdapter;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

public class BuckTargetsPanel {

  public static final String DISPLAY_NAME = "Targets";
  public static final String TREE_ROOT = "Buck";
  public static final String TARGETS_ROOT = "Targets";
  public static final String TESTS_ROOT = "Tests";

  private final BuckPluginComponent component;
  private JPanel targetsPanel;
  private JButton refreshTargetsButton;
  private JButton cleanButton;
  private JButton testAllButton;
  private JTree tree;
  @SuppressWarnings("unused")
  private JScrollPane scrollPane;
  private DefaultTreeModel treeModel;
  private TargetNode treeRoot;
  private TargetNode targetsRoot;
  private TargetNode testsRoot;

  public BuckTargetsPanel(BuckPluginComponent component) {
    this.component = Preconditions.checkNotNull(component);
  }

  public JPanel getPanel() {
    return targetsPanel;
  }

  @SuppressWarnings("unused")
  private void createUIComponents() {
    targetsPanel = new JPanel();
    targetsPanel.addAncestorListener(new AncestorListener() {
      @Override
      public void ancestorAdded(AncestorEvent event) {
        // If targets have never been fetched, then fetch targets.
        if (component.getTargets() == null) {
          component.refreshTargetsList();
        }
      }

      @Override
      public void ancestorRemoved(AncestorEvent event) {
      }

      @Override
      public void ancestorMoved(AncestorEvent event) {
      }
    });

    // Tree
    treeRoot = new TargetNode(TargetNode.Type.DIRECTORY, TREE_ROOT, null);
    targetsRoot = new TargetNode(TargetNode.Type.DIRECTORY, TARGETS_ROOT, null);
    testsRoot = new TargetNode(TargetNode.Type.DIRECTORY, TESTS_ROOT, null);
    tree = new Tree(new DefaultTreeModel(treeRoot));
    scrollPane = TargetsTreeRenderer.install(tree);

    final MouseInputAdapter mouseListener = new MouseInputAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
          // Double click
          TreePath selected = tree.getSelectionPath();
          TargetNode targetNode = (TargetNode) selected.getLastPathComponent();
          BuckTarget target = targetNode.getTarget();
          if (target != null) {
            if (targetNode.getType() == TargetNode.Type.JAVA_TEST ||
                targetNode.getType() == TargetNode.Type.SH_TEST) {
              component.testTarget(target);
            } else {
              component.buildTarget(target);
            }
          }
        }
      }
    };
    tree.addMouseListener(mouseListener);

    // Buttons
    refreshTargetsButton = createToolbarIcon();
    refreshTargetsButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        component.refreshTargetsList();
      }
    });

    cleanButton = createToolbarIcon();
    cleanButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        component.clean();
      }
    });

    testAllButton = createToolbarIcon();
    testAllButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        component.testAllTargets();
      }
    });
  }

  private JButton createToolbarIcon() {
    JButton button = new JButton();
    return button;
  }

  public void updateTargets() {
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run() {
        treeModel = new DefaultTreeModel(treeRoot);
        treeModel.insertNodeInto(targetsRoot, treeRoot, treeRoot.getChildCount());
        treeModel.insertNodeInto(testsRoot, treeRoot, treeRoot.getChildCount());
        for (BuckTarget target : component.getTargets()) {
          TargetNode node = target.createTreeNode();
          TargetNode parent;
          if (node.getType() == TargetNode.Type.JAVA_TEST) {
            parent = testsRoot;
          } else {
            parent = targetsRoot;
          }
          treeModel.insertNodeInto(node, parent, parent.getChildCount());
        }
        tree.setModel(treeModel);
      }
    });
  }
}
