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

import java.util.Enumeration;
import javax.swing.tree.TreeNode;

public class BuckTreeNodeDetail implements TreeNode {

  public enum DetailType {
    ERROR,
    WARNING,
    INFO
  }

  protected DetailType mType;
  protected String mDetail;
  protected TreeNode mParent;

  public BuckTreeNodeDetail(TreeNode parent, DetailType type, String detail) {
    mType = type;
    mDetail = detail;
    mParent = parent;
  }

  @Override
  public TreeNode getChildAt(int childIndex) {
    return null;
  }

  @Override
  public int getChildCount() {
    return 0;
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
    return false;
  }

  @Override
  public boolean isLeaf() {
    return true;
  }

  @Override
  public Enumeration children() {
    return null;
  }

  public String getDetail() {
    return mDetail;
  }

  public void setDetail(String detail) {
    mDetail = detail;
  }

  public DetailType getType() {
    return mType;
  }
}
