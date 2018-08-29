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

import com.facebook.buck.intellij.ideabuck.ui.tree.BuckErrorItemNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckFileErrorNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.renderers.BuckTreeCellRenderer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

public class BuckTreeViewPanel implements BuckToolWindowPanel {

  private static final String TREE_VIEW_PANEL = "BuckTreeViewPanel";

  private JScrollPane scrollPane;
  private BuckTextNode mRoot;
  private ModifiableModel mModifiableModel;

  public BuckTreeViewPanel() {
    mRoot = new BuckTextNode("", BuckTextNode.TextType.INFO);
    mModifiableModel = new ModifiableModel(mRoot);
    scrollPane = new JBScrollPane(mModifiableModel.getTree());
    scrollPane.setAutoscrolls(true);
  }

  private static void handleClickOnError(BuckErrorItemNode node) {
    TreeNode parentNode = node.getParent();
    if (parentNode instanceof BuckFileErrorNode) {
      BuckFileErrorNode buckParentNode = (BuckFileErrorNode) parentNode;
      DataContext dataContext = DataManager.getInstance().getDataContext();
      Project project = DataKeys.PROJECT.getData(dataContext);

      String relativePath = buckParentNode.getText().replace(project.getBasePath(), "");

      VirtualFile virtualFile = project.getBaseDir().findFileByRelativePath(relativePath);
      if (virtualFile != null) {
        OpenFileDescriptor openFileDescriptor =
            new OpenFileDescriptor(project, virtualFile, node.getLine() - 1, node.getColumn() - 1);
        openFileDescriptor.navigate(true);
      }
    }
  }

  public synchronized BuckTextNode getRoot() {
    return mRoot;
  }

  public ModifiableModel getModifiableModel() {
    return mModifiableModel;
  }

  public static class ModifiableModel {
    private final DefaultTreeModel mTreeModel;
    private Tree mTree;
    private TreeModelListener mDefaultTreeModelListener;

    public ModifiableModel(BuckTextNode root) {
      mTreeModel = new DefaultTreeModel(root);
      mTree =
          new Tree(mTreeModel) {
            @Override
            public int getScrollableUnitIncrement(
                Rectangle visibleRect, int orientation, int direction) {
              return 5;
            }
          };
      mDefaultTreeModelListener =
          new TreeModelListener() {
            @Override
            public void treeNodesChanged(TreeModelEvent e) {}

            @Override
            public void treeNodesInserted(TreeModelEvent e) {
              if (e.getTreePath().getLastPathComponent() == mTreeModel.getRoot()) {
                ApplicationManager.getApplication()
                    .invokeLater(() -> mTree.expandPath(e.getTreePath()));
              }
            }

            @Override
            public void treeNodesRemoved(TreeModelEvent e) {}

            @Override
            public void treeStructureChanged(TreeModelEvent e) {}
          };
      mTreeModel.addTreeModelListener(mDefaultTreeModelListener);
      mTree.addMouseListener(
          new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent e) {
              Tree tree = (Tree) e.getComponent();
              int selRow = tree.getRowForLocation(e.getX(), e.getY());
              TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
              if (selRow != -1 && e.getClickCount() == 2) {
                TreeNode node = (TreeNode) selPath.getLastPathComponent();
                if (node.isLeaf()) {
                  BuckTextNode buckNode = (BuckTextNode) node;
                  if (buckNode instanceof BuckErrorItemNode) {
                    BuckTreeViewPanel.handleClickOnError((BuckErrorItemNode) buckNode);
                  }
                }
              }
            }

            @Override
            public void mousePressed(MouseEvent e) {}

            @Override
            public void mouseReleased(MouseEvent e) {}

            @Override
            public void mouseEntered(MouseEvent e) {}

            @Override
            public void mouseExited(MouseEvent e) {}
          });
      mTree.setCellRenderer(new BuckTreeCellRenderer());
      mTree.setShowsRootHandles(false);
      mTree.setRowHeight(0);
      mTree.setScrollsOnExpand(true);
    }

    private Tree getTree() {
      return mTree;
    }

    public void addTreeModelListener(Function<Tree, TreeModelListener> treeModelListenerFunction) {
      synchronized (mTreeModel) {
        mTreeModel.removeTreeModelListener(mDefaultTreeModelListener);
        mTreeModel.addTreeModelListener(treeModelListenerFunction.apply(mTree));
      }
    }

    public void removeTreeModelListener(TreeModelListener treeModelListener) {
      synchronized (mTreeModel) {
        mTreeModel.removeTreeModelListener(treeModelListener);
      }
    }

    public void addDefaultTreeModelListener() {
      addTreeModelListener(_tree -> mDefaultTreeModelListener);
    }

    public void removeDefaultTreeModelListener() {
      removeTreeModelListener(mDefaultTreeModelListener);
    }

    public ModifiableModel invokeLater(Consumer<DefaultTreeModel> consumer, boolean syncAfter) {
      ApplicationManager.getApplication()
          .invokeLater(
              () -> {
                synchronized (mTreeModel) {
                  consumer.accept(mTreeModel);
                }
                if (syncAfter) {
                  FileDocumentManager.getInstance().saveAllDocuments();
                  VirtualFileManager.getInstance().refreshWithoutFileWatcher(true);
                }
              });
      return this;
    }

    public void addChild(BuckTextNode parent, BuckTextNode child, boolean syncAfter) {
      invokeLater(
          treeModel -> {
            treeModel.insertNodeInto(child, parent, parent.getChildCount());
          },
          syncAfter);
    }

    public void addChild(BuckTextNode parent, BuckTextNode child) {
      addChild(parent, child, false);
    }

    public void addChildren(BuckTextNode parent, List<BuckTextNode> children, boolean syncAfter) {
      invokeLater(
          treeModel -> {
            int[] indexs = new int[children.size()];
            for (int i = 0; i < indexs.length; i++) {
              indexs[i] = parent.getChildCount();
              parent.insert(children.get(i), indexs[i]);
            }
            treeModel.nodesWereInserted(parent, indexs);
          },
          syncAfter);
    }

    public void addChildren(BuckTextNode parent, List<BuckTextNode> children) {
      addChildren(parent, children, false);
    }

    public void setNodeText(
        BuckTextNode node, String text, BuckTextNode.TextType textType, boolean syncAfter) {
      invokeLater(
          treeModel -> {
            node.getNodeObject().setText(text);
            node.getNodeObject().setTextType(textType);
            treeModel.nodeChanged(node);
          },
          syncAfter);
    }

    public void setNodeText(BuckTextNode node, String text, BuckTextNode.TextType textType) {
      setNodeText(node, text, textType, false);
    }

    public void setNodeText(BuckTextNode node, String text, boolean syncAfter) {
      invokeLater(
          treeModel -> {
            node.getNodeObject().setText(text);
            treeModel.nodeChanged(node);
          },
          syncAfter);
    }

    public void setNodeText(BuckTextNode node, String text) {
      setNodeText(node, text, false);
    }

    public void removeAllChildren(BuckTextNode node, boolean syncAfter) {
      invokeLater(
          treeModel -> {
            if (node.getChildCount() > 0) {
              int[] childIndices = new int[node.getChildCount()];
              Object[] removedChildren = new Object[node.getChildCount()];
              for (int i = 0; i < childIndices.length; i++) {
                childIndices[i] = i;
                removedChildren[i] = node.getChildAt(i);
              }
              node.removeAllChildren();
              treeModel.nodesWereRemoved(node, childIndices, removedChildren);
            }
          },
          syncAfter);
    }

    public void removeAllChildren(BuckTextNode node) {
      removeAllChildren(node, false);
    }
  }

  @Override
  public String getId() {
    return TREE_VIEW_PANEL;
  }

  @Override
  public String getTitle() {
    return "Event View";
  }

  @Override
  public JComponent getComponent() {
    return scrollPane;
  }
}
