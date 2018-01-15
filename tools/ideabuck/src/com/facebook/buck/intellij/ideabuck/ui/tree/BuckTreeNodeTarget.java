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

package com.facebook.buck.intellij.ideabuck.ui.tree;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import javax.swing.tree.TreeNode;

public class BuckTreeNodeTarget implements TreeNode {

  private String mTarget;
  private List<BuckTreeNodeFileError> mFileError;
  private BuckTreeNodeBuild mParent;

  public BuckTreeNodeTarget(BuckTreeNodeBuild build, String target) {
    mTarget = target;
    mParent = build;
    mFileError = new ArrayList<BuckTreeNodeFileError>();
  }

  public void addFileError(BuckTreeNodeFileError error) {
    mFileError.add(error);
  }

  @Override
  public TreeNode getChildAt(int childIndex) {
    return mFileError.get(childIndex);
  }

  @Override
  public int getChildCount() {
    return mFileError.size();
  }

  @Override
  public TreeNode getParent() {
    return mParent;
  }

  @Override
  public int getIndex(TreeNode node) {
    return 0;
  }

  @Override
  public boolean getAllowsChildren() {
    return true;
  }

  @Override
  public boolean isLeaf() {
    return false;
  }

  @Override
  public Enumeration children() {
    return new Enumeration<TreeNode>() {
      private int currentIndex = 0;

      @Override
      public boolean hasMoreElements() {
        if (currentIndex >= BuckTreeNodeTarget.this.mFileError.size()) {
          return false;
        }
        return true;
      }

      @Override
      public TreeNode nextElement() {
        return BuckTreeNodeTarget.this.mFileError.get(++currentIndex);
      }
    };
  }

  public String getTarget() {
    return mTarget;
  }
}
