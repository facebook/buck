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

import com.facebook.buck.plugin.intellij.commands.event.RuleEnd;
import com.facebook.buck.plugin.intellij.commands.event.RuleStart;
import com.facebook.buck.plugin.intellij.commands.event.TestResultsAvailable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.tree.TreeUtil;

import java.awt.EventQueue;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

public class BuckProgressPanel {

  public static final String DISPLAY_NAME = "Progress";
  public static final String TREE_ROOT = "Buck";
  public static final String BUILDING_ROOT = "Building";
  public static final String BUILT_ROOT = "Built";
  private static final String TEST_ROOT = "Test";

  private JPanel panel;
  private JTree tree;
  @SuppressWarnings("unused")
  private JScrollPane scrollPane;
  private DefaultTreeModel treeModel;
  private ProgressNode treeRoot;
  private ProgressNode buildingRoot;
  private ProgressNode builtRoot;
  private ProgressNode testRoot;
  private TreePath rootPath;
  private TreePath buildingPath;
  private TreePath builtPath;
  private TreePath testPath;
  private List<ProgressNode> items;

  public JPanel getPanel() {
    return panel;
  }

  public void clear() {
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run() {
        items.clear();
        createModel();
        tree.setModel(treeModel);
      }
    });
  }

  public void startRule(RuleStart event) {
    Preconditions.checkNotNull(event);
    final ProgressNode node = event.createTreeNode();
    items.add(node);
    // Swing UI manipulation must be in event loop thread
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run() {
        treeModel.insertNodeInto(node, buildingRoot, buildingRoot.getChildCount());
        expand();
        scrollTo(node);
      }
    });
  }

  public void endRule(final RuleEnd event) {
    Preconditions.checkNotNull(event);
    final ProgressNode node = findBuildingNode(event);
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run() {
        treeModel.removeNodeFromParent(node);
        event.updateTreeNode(node);
        treeModel.insertNodeInto(node, builtRoot, builtRoot.getChildCount());
        expand();
        scrollTo(node);
      }
    });
  }

  public void testResult(TestResultsAvailable event) {
    Preconditions.checkNotNull(event);
    final ImmutableList<ProgressNode> nodes = event.createTreeNodes();
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run() {
        for (ProgressNode node : nodes) {
          treeModel.insertNodeInto(node, testRoot, testRoot.getChildCount());
          expand();
          scrollTo(node);
        }
      }
    });
  }

  private ProgressNode findBuildingNode(RuleEnd current) {
    Preconditions.checkNotNull(current);
    for (ProgressNode item : items) {
      if (item.getType() == ProgressNode.Type.BUILDING) {
        RuleStart event = (RuleStart) item.getEvent();
        if (Preconditions.checkNotNull(event).matchesEndRule(current)) {
          return item;
        }
      }
    }
    throw new RuntimeException("Node of rule_start that matches rule_end not found!");
  }

  @SuppressWarnings("unused")
  private void createUIComponents() {
    createModel();
    tree = new Tree(treeModel);
    items = Lists.newArrayList();
    scrollPane = MessageTreeRenderer.install(tree);
  }

  private void createModel() {
    treeRoot = new ProgressNode(ProgressNode.Type.DIRECTORY, TREE_ROOT, null);
    rootPath = new TreePath(treeRoot);
    treeModel = new DefaultTreeModel(treeRoot);

    builtRoot = new ProgressNode(ProgressNode.Type.DIRECTORY, BUILT_ROOT, null);
    builtPath = new TreePath(builtRoot);
    treeModel.insertNodeInto(builtRoot, treeRoot, treeRoot.getChildCount());

    buildingRoot = new ProgressNode(ProgressNode.Type.DIRECTORY, BUILDING_ROOT, null);
    buildingPath = new TreePath(buildingRoot);
    treeModel.insertNodeInto(buildingRoot, treeRoot, treeRoot.getChildCount());

    testRoot = new ProgressNode(ProgressNode.Type.DIRECTORY, TEST_ROOT, null);
    testPath = new TreePath(testRoot);
    treeModel.insertNodeInto(testRoot, treeRoot, treeRoot.getChildCount());
  }

  private void expand() {
    if (!tree.hasBeenExpanded(rootPath)) {
      tree.expandPath(rootPath);
    }
    if (!tree.hasBeenExpanded(buildingPath)) {
      tree.expandPath(buildingPath);
    }
    if (!tree.hasBeenExpanded(builtPath)) {
      tree.expandPath(builtPath);
    }
    if (!tree.hasBeenExpanded(testPath)) {
      tree.expandPath(testPath);
    }
  }

  private void scrollTo(ProgressNode node) {
    TreeUtil.selectPath(tree, new TreePath(node.getPath()));
  }
}
