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

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.Objects;
import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

public class TreesMessager implements Messager {
  private final Trees trees;

  public TreesMessager(Trees trees) {
    this.trees = trees;
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
    printMessage(kind, msg, Objects.requireNonNull(trees.getPath(e)));
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
    printMessage(kind, msg, Objects.requireNonNull(trees.getPath(e, a)));
  }

  @Override
  public void printMessage(
      Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
    printMessage(kind, msg, Objects.requireNonNull(trees.getPath(e, a, v)));
  }

  private void printMessage(Diagnostic.Kind kind, CharSequence msg, TreePath path) {
    trees.printMessage(kind, msg, path.getLeaf(), path.getCompilationUnit());
  }
}
