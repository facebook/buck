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

import javax.swing.tree.DefaultMutableTreeNode;

public class BuckTextNode extends DefaultMutableTreeNode {
  public enum TextType {
    ERROR,
    WARNING,
    INFO
  }

  public static class NodeObject {
    private String mText;
    private TextType mTextType;

    public NodeObject(String text, TextType textType) {
      mText = text;
      mTextType = textType;
    }

    private TextType getTextType() {
      return mTextType;
    }

    public void setTextType(TextType textType) {
      mTextType = textType;
    }

    private String getText() {
      return mText;
    }

    public void setText(String text) {
      mText = text;
    }
  }

  public BuckTextNode(String text, TextType textType) {
    setUserObject(new NodeObject(text, textType));
  }

  public String getText() {
    return ((NodeObject) getUserObject()).getText();
  }

  public NodeObject getNodeObject() {
    return (NodeObject) getUserObject();
  }

  public TextType getTextType() {
    return ((NodeObject) getUserObject()).getTextType();
  }
}
