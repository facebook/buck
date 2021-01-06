/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.test.util;

import javax.swing.SwingUtilities;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;

public class MockTreeModelListener implements TreeModelListener {
  public boolean calledOnWrongThread = false;

  public void checkThread() {
    calledOnWrongThread |= !SwingUtilities.isEventDispatchThread();
  }

  @Override
  public void treeNodesChanged(TreeModelEvent e) {
    checkThread();
  }

  @Override
  public void treeNodesInserted(TreeModelEvent e) {
    checkThread();
  }

  @Override
  public void treeNodesRemoved(TreeModelEvent e) {
    checkThread();
  }

  @Override
  public void treeStructureChanged(TreeModelEvent e) {
    checkThread();
  }
}
