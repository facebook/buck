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

package com.facebook.buck.jvm.java.plugin.adapter;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePathScanner;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.element.TypeElement;

/** A {@link TaskListener} that runs some code after the final enter phase. */
public class PostEnterTaskListener implements TaskListener {
  private final BuckJavacTask task;
  private final Consumer<Set<TypeElement>> callback;
  private final Set<TypeElement> topLevelTypes = new LinkedHashSet<TypeElement>();

  private boolean annotationProcessing = false;
  private int pendingEnterCalls = 0;

  public PostEnterTaskListener(BuckJavacTask task, Consumer<Set<TypeElement>> callback) {
    this.task = task;
    this.callback = callback;
  }

  @Override
  public void started(TaskEvent e) {
    switch (e.getKind()) {
      case ANNOTATION_PROCESSING:
        // The raw event stream from javac will start with this event if there are any APs present
        annotationProcessing = true;
        break;
      case ENTER:
        if (pendingEnterCalls == 0) {
          topLevelTypes.clear();
        }
        pendingEnterCalls += 1;
        break;
        // $CASES-OMITTED$
      default:
        break;
    }
  }

  @Override
  public void finished(TaskEvent e) {
    switch (e.getKind()) {
      case ANNOTATION_PROCESSING:
        annotationProcessing = false;
        break;
      case ENTER:
        pendingEnterCalls -= 1;
        CompilationUnitTree compilationUnit = e.getCompilationUnit();
        new TreePathScanner<Void, Void>() {
          @Override
          public Void visitCompilationUnit(CompilationUnitTree node, Void aVoid) {
            return super.scan(node.getTypeDecls(), null);
          }

          @Override
          public Void visitClass(ClassTree node, Void aVoid) {
            TypeElement typeElement = (TypeElement) task.getTrees().getElement(getCurrentPath());
            topLevelTypes.add(typeElement);
            return null;
          }
        }.scan(compilationUnit, null);
        break;
        // $CASES-OMITTED$
      default:
        break;
    }

    if (e.getKind() == TaskEvent.Kind.ENTER && !annotationProcessing && pendingEnterCalls == 0) {
      Set<TypeElement> unmodifiableTopLevelTypes = Collections.unmodifiableSet(topLevelTypes);
      callback.accept(unmodifiableTopLevelTypes);
    }
  }
}
