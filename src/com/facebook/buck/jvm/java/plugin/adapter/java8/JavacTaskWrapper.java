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

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;
import java.io.IOException;
import java.util.Locale;
import javax.annotation.processing.Processor;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

/**
 * NOTE: A Java 11 copy of this file exists in ../java11/JavacTaskWrapper.java. Please make sure to
 * update the other copy when modifying this file.
 */
abstract class JavacTaskWrapper extends JavacTask {
  protected final JavacTask inner;

  public JavacTaskWrapper(JavacTask inner) {
    this.inner = inner;
  }

  @Override
  public Iterable<? extends CompilationUnitTree> parse() throws IOException {
    return inner.parse();
  }

  @Override
  public Iterable<? extends Element> analyze() throws IOException {
    return inner.analyze();
  }

  @Override
  public Iterable<? extends JavaFileObject> generate() throws IOException {
    return inner.generate();
  }

  @Override
  public void setTaskListener(TaskListener taskListener) {
    inner.setTaskListener(taskListener);
  }

  @Override
  public void addTaskListener(TaskListener taskListener) {
    inner.addTaskListener(taskListener);
  }

  @Override
  public void removeTaskListener(TaskListener taskListener) {
    inner.removeTaskListener(taskListener);
  }

  @Override
  public TypeMirror getTypeMirror(Iterable<? extends Tree> path) {
    return inner.getTypeMirror(path);
  }

  @Override
  public Elements getElements() {
    return inner.getElements();
  }

  @Override
  public Types getTypes() {
    return inner.getTypes();
  }

  @Override
  public void setProcessors(Iterable<? extends Processor> processors) {
    inner.setProcessors(processors);
  }

  @Override
  public void setLocale(Locale locale) {
    inner.setLocale(locale);
  }

  @Override
  public Boolean call() {
    return inner.call();
  }
}
