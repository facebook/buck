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

public class BuckFileErrorNode extends BuckTextNode {

  public BuckFileErrorNode(String text) {
    super(text, TextType.ERROR);
  }

  public void addErrorItem(CompilerErrorItem error) {
    if (error.getType() == CompilerErrorItem.Type.ERROR) {
      add(new BuckErrorItemNode(error.getError(), error.getLine(), error.getColumn()));
    } else {
      add(new BuckTextNode(error.getError(), BuckTextNode.TextType.WARNING));
    }
  }
}
