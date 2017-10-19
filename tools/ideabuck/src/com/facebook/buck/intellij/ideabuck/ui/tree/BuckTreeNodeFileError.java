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

import com.facebook.buck.intellij.ideabuck.ui.utils.CompilerErrorItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.swing.tree.TreeNode;

public class BuckTreeNodeFileError implements TreeNode {

  private final List<BuckTreeNodeDetail> mErrors = new ArrayList<BuckTreeNodeDetail>();
  private final String mFilePath;
  private final BuckTreeNodeTarget mParent;

  public BuckTreeNodeFileError(
      BuckTreeNodeTarget parent, String filePath, CompilerErrorItem error) {
    mParent = parent;
    mFilePath = filePath;
    mErrors.add(buildError(error));
  }

  public void addError(CompilerErrorItem error) {
    mErrors.add(buildError(error));
  }

  private BuckTreeNodeDetail buildError(CompilerErrorItem error) {
    if (error.getType() == CompilerErrorItem.Type.ERROR) {
      return new BuckTreeNodeDetailError(
          this, error.getError(), error.getLine(), error.getColumn());
    } else {
      return new BuckTreeNodeDetail(this, BuckTreeNodeDetail.DetailType.WARNING, error.getError());
    }
  }

  @Override
  public TreeNode getChildAt(int childIndex) {
    return mErrors.get(childIndex);
  }

  @Override
  public int getChildCount() {
    return mErrors.size();
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
    return Collections.enumeration(mErrors);
  }

  public String getFilePath() {
    return mFilePath;
  }
}
