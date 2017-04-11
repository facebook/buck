/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.processing.Processor;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;

/**
 * An implementation of {@link JavacTask} that implements only the frontend portions of the task,
 * using only the parse phase of the underlying compiler and without requiring a complete
 * classpath. This effectively does the same thing as the Enter phase of the compiler (see
 * http://openjdk.java.net/groups/compiler/doc/compilation-overview/index.html), but applying
 * heuristics when dependencies are missing. This necessarily requires some assumptions to be made
 * about references to symbols defined in those dependencies. See the documentation of
 * {@link com.facebook.buck.jvm.java.abi.source} for details.
 */
public class FrontendOnlyJavacTask extends BuckJavacTask {
  private final JavacTask javacTask;
  private final Elements javacElements;
  private final Trees javacTrees;
  private final TreeBackedElements elements;
  private final TreeBackedTrees trees;
  private final TreeBackedTypes types;

  @Nullable
  private Iterable<? extends CompilationUnitTree> parsedCompilationUnits;
  @Nullable
  private List<TreeBackedTypeElement> topLevelElements;

  public FrontendOnlyJavacTask(JavacTask javacTask) {
    super(javacTask);
    this.javacTask = javacTask;
    javacElements = javacTask.getElements();
    javacTrees = Trees.instance(javacTask);
    types = new TreeBackedTypes(javacTask.getTypes());
    elements = new TreeBackedElements(javacElements, javacTrees);
    trees = new TreeBackedTrees(javacTrees, elements, types);
    types.setElements(elements);
    elements.setResolver(new TreeBackedElementResolver(elements, types));
    javacTask.setTaskListener(new EnteringTaskListener(elements, trees));
  }

  @Override
  public Iterable<? extends CompilationUnitTree> parse() throws IOException {
    if (parsedCompilationUnits == null) {
      parsedCompilationUnits = javacTask.parse();
    }

    return parsedCompilationUnits;
  }

  @Override
  public Iterable<? extends TypeElement> enter() throws IOException {
    Iterable<? extends TypeElement> javacTopLevelElements = super.enter();

    topLevelElements = StreamSupport.stream(javacTopLevelElements.spliterator(), false)
        .map(elements::getCanonicalElement)
        .map(element -> (TreeBackedTypeElement) element)
        .collect(Collectors.toList());

    return topLevelElements;
  }

  @Override
  public Iterable<? extends Element> analyze() throws IOException {
    throw new UnsupportedOperationException("Code analysis not supported");
  }

  @Override
  public Iterable<? extends JavaFileObject> generate() throws IOException {
    throw new UnsupportedOperationException("Code generation not supported");
  }

  @Override
  public void setTaskListener(TaskListener taskListener) {
    throw new UnsupportedOperationException("NYI");
  }

  @Override
  public void addTaskListener(TaskListener taskListener) {
    throw new UnsupportedOperationException("NYI");
  }

  @Override
  public void removeTaskListener(TaskListener taskListener) {
    throw new UnsupportedOperationException("NYI");
  }

  @Override
  public TypeMirror getTypeMirror(Iterable<? extends Tree> path) {
    throw new UnsupportedOperationException();
  }

  /* package */ JavacTask getInnerTask() {
    return javacTask;
  }

  @Override
  public TreeBackedElements getElements() {
    return elements;
  }

  public TreeBackedTrees getTrees() {
    return trees;
  }

  @Override
  public TreeBackedTypes getTypes() {
    return types;
  }

  @Override
  public void setProcessors(Iterable<? extends Processor> processors) {
    if (processors.iterator().hasNext()) {
      javacTask.setProcessors(wrap(processors));
    }
  }

  private List<TreeBackedProcessorWrapper> wrap(Iterable<? extends Processor> processors) {
    List<TreeBackedProcessorWrapper> result = new ArrayList<>();
    for (Processor processor : processors) {
      result.add(new TreeBackedProcessorWrapper(elements, types, processor));
    }

    return result;
  }

  @Override
  public void setLocale(Locale locale) {
    throw new UnsupportedOperationException("NYI");
  }

  @Override
  public Boolean call() {
    throw new UnsupportedOperationException("NYI");
  }
}
