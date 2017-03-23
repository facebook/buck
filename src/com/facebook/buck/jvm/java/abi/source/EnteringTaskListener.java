/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source;

import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

/**
 * Watches the phases of the compiler and keeps our parallel symbol table in sync.
 */
class EnteringTaskListener implements TaskListener {
  private final TreeBackedElements elements;
  private final TreeBackedTrees trees;
  private final TreeBackedEnter enter;

  public EnteringTaskListener(TreeBackedElements elements, TreeBackedTrees trees) {
    this.elements = elements;
    this.trees = trees;
    enter = new TreeBackedEnter(elements, trees);
  }

  @Override
  public void started(TaskEvent e) {
  }

  @Override
  public void finished(TaskEvent e) {
    if (e.getKind() == TaskEvent.Kind.ENTER) {
      enter.enter(e.getCompilationUnit());
    } else if (e.getKind() == TaskEvent.Kind.ANNOTATION_PROCESSING_ROUND) {
      elements.clear();
      trees.clear();
    }
  }
}
