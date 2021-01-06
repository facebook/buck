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

package com.facebook.buck.jvm.java.abi.source;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import java.util.ArrayList;
import java.util.List;

/** Calls back when the ENTER phase is complete. */
abstract class PostEnterCallback implements TaskListener {
  private List<CompilationUnitTree> compilationUnits = new ArrayList<>();
  private int enterCount = 0;

  @Override
  public void started(TaskEvent e) {
    if (e.getKind() == TaskEvent.Kind.ENTER) {
      enterCount += 1;
    }
  }

  @Override
  public void finished(TaskEvent e) {
    switch (e.getKind()) {
      case PARSE:
        compilationUnits.add(e.getCompilationUnit());
        break;
      case ENTER:
        enterCount -= 1;
        if (enterCount == 0) {
          enterComplete(compilationUnits);
        }
        break;
        // $CASES-OMITTED$
      default:
        break;
    }
  }

  protected abstract void enterComplete(List<CompilationUnitTree> compilationUnits);
}
