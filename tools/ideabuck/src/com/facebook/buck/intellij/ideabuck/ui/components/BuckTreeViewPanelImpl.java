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

public class BuckTreeViewPanelImpl implements BuckTreeViewPanel {
  private static final String TREE_VIEW_PANEL = "BuckTreeViewPanel";

  private JScrollPane mScrollPane;
  private BuckTextNode mRoot;
  private ModifiableModel mModifiableModel;

  public BuckTreeViewPanelImpl() {
    mRoot = new BuckTextNode("", BuckTextNode.TextType.INFO);
    DefaultTreeModel treeModel = new DefaultTreeModel(mRoot);
    mModifiableModel =
        new ModifiableModelImpl(
            treeModel,
            new Tree(treeModel) {
              @Override
              public int getScrollableUnitIncrement(
                  Rectangle visibleRect, int orientation, int direction) {
                return 5;
              }
            });
    mScrollPane = new JBScrollPane(mModifiableModel.getTree());
    mScrollPane.setAutoscrolls(true);
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

  @Override
  public synchronized BuckTextNode getRoot() {
    return mRoot;
  }

  @Override
  public ModifiableModel getModifiableModel() {
    return mModifiableModel;
  }

  public static class ModifiableModelImpl implements ModifiableModel {
    private final DefaultTreeModel mTreeModel;
    private Tree mTree;
    private TreeModelListener mDefaultTreeModelListener;
    private boolean mScrollToEnd;

    public ModifiableModelImpl(DefaultTreeModel treeModel, Tree tree) {
      mTreeModel = treeModel;
      mTree = tree;
      mDefaultTreeModelListener =
          new TreeModelListener() {
            @Override
            public void treeNodesChanged(TreeModelEvent e) {}

            @Override
            public void treeNodesInserted(TreeModelEvent e) {
              ApplicationManager.getApplication()
                  .invokeLater(
                      () -> {
                        mTree.expandPath(e.getTreePath());
                        if (mScrollToEnd) {
                          scrollToEnd();
                        }
                      });
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
              if (selRow != -1) {
                setScrollToEnd(false);
              }
              if (selRow != -1 && e.getClickCount() == 2) {
                TreeNode node = (TreeNode) selPath.getLastPathComponent();
                if (node.isLeaf()) {
                  BuckTextNode buckNode = (BuckTextNode) node;
                  if (buckNode instanceof BuckErrorItemNode) {
                    handleClickOnError((BuckErrorItemNode) buckNode);
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
      mScrollToEnd = true;
    }

    @Override
    public Tree getTree() {
      return mTree;
    }

    @Override
    public void addTreeModelListener(Function<Tree, TreeModelListener> treeModelListenerFunction) {
      synchronized (mTreeModel) {
        mTreeModel.removeTreeModelListener(mDefaultTreeModelListener);
        mTreeModel.addTreeModelListener(treeModelListenerFunction.apply(mTree));
      }
    }

    @Override
    public void removeTreeModelListener(TreeModelListener treeModelListener) {
      synchronized (mTreeModel) {
        mTreeModel.removeTreeModelListener(treeModelListener);
      }
    }

    @Override
    public void addDefaultTreeModelListener() {
      addTreeModelListener(_tree -> mDefaultTreeModelListener);
    }

    @Override
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

    @Override
    public void addChild(BuckTextNode parent, BuckTextNode child, boolean syncAfter) {
      invokeLater(
          treeModel -> {
            treeModel.insertNodeInto(child, parent, parent.getChildCount());
          },
          syncAfter);
    }

    @Override
    public void addChild(BuckTextNode parent, BuckTextNode child) {
      addChild(parent, child, false);
    }

    @Override
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

    @Override
    public void addChildren(BuckTextNode parent, List<BuckTextNode> children) {
      addChildren(parent, children, false);
    }

    @Override
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

    @Override
    public void setNodeText(BuckTextNode node, String text, BuckTextNode.TextType textType) {
      setNodeText(node, text, textType, false);
    }

    @Override
    public void setNodeText(BuckTextNode node, String text, boolean syncAfter) {
      invokeLater(
          treeModel -> {
            node.getNodeObject().setText(text);
            treeModel.nodeChanged(node);
          },
          syncAfter);
    }

    @Override
    public void setNodeText(BuckTextNode node, String text) {
      setNodeText(node, text, false);
    }

    @Override
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

    @Override
    public void removeAllChildren(BuckTextNode node) {
      removeAllChildren(node, false);
    }

    @Override
    public boolean shouldScrollToEnd() {
      return mScrollToEnd;
    }

    @Override
    public void setScrollToEnd(boolean scrollToEnd) {
      mScrollToEnd = scrollToEnd;
    }

    @Override
    public void scrollToEnd() {
      Rectangle rowRect = mTree.getRowBounds(mTree.getRowCount() - 1);
      Rectangle viewRect = mTree.getVisibleRect();
      if (rowRect != null && viewRect != null) {
        viewRect.y = rowRect.y;
        mTree.scrollRectToVisible(viewRect);
      }
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
    return mScrollPane;
  }
}
