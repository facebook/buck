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

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

class TreeBackedMessager implements Messager {
  private final Messager javacMessager;
  private final FrontendOnlyJavacTask task;

  public TreeBackedMessager(FrontendOnlyJavacTask task, Messager javacMessager) {
    this.task = task;
    this.javacMessager = javacMessager;
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
    javacMessager.printMessage(kind, msg);
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
    javacMessager.printMessage(kind, msg, task.getElements().getJavacElement(e));
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
    javacMessager.printMessage(
        kind, msg, task.getElements().getJavacElement(e), task.getElements().getJavacAnnotation(a));
  }

  @Override
  public void printMessage(
      Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
    javacMessager.printMessage(
        kind,
        msg,
        task.getElements().getJavacElement(e),
        task.getElements().getJavacAnnotation(a),
        task.getElements().getJavacAnnotationValue(v));
  }
}
