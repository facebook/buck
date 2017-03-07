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

import com.facebook.buck.event.api.BuckTracing;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.processing.Processor;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
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
public class FrontendOnlyJavacTask extends JavacTask {
  private static final BuckTracing BUCK_TRACING = BuckTracing.getInstance("TreeResolver");
  private final JavacTask javacTask;
  private final TreeBackedElements elements;
  private final TreeBackedTrees trees;
  private final TreeBackedTypes types;
  private final TypeResolverFactory resolverFactory;

  @Nullable
  private Iterable<? extends CompilationUnitTree> parsedCompilationUnits;
  @Nullable
  private List<TreeBackedTypeElement> topLevelElements;

  public FrontendOnlyJavacTask(JavacTask javacTask) {
    this.javacTask = javacTask;
    elements = new TreeBackedElements(javacTask.getElements());
    trees = new TreeBackedTrees(Trees.instance(javacTask), elements);
    types = new TreeBackedTypes();
    resolverFactory = new TypeResolverFactory(elements, types, trees);
    elements.setResolverFactory(resolverFactory);
  }

  @Override
  public Iterable<? extends CompilationUnitTree> parse() throws IOException {
    if (parsedCompilationUnits == null) {
      parsedCompilationUnits = javacTask.parse();
    }

    return parsedCompilationUnits;
  }

  public Iterable<? extends TypeElement> enter() throws IOException {
    if (topLevelElements == null) {
      topLevelElements = StreamSupport.stream(parse().spliterator(), false)
          .map(this::enterTree)
          .flatMap(List::stream)
          .collect(Collectors.toList());
    }

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

  // TODO(jkeljo): Get a java 8 stub to compile against and then uncomment the below:
  // @Override
  public void addTaskListener(TaskListener taskListener) {
    // If check is just here to shut up the compiler about the unused parameter. Updating our tools
    // stub to java 8 will fix that too
    if (taskListener != null) {
      throw new UnsupportedOperationException("NYI");
    }
  }

  // TODO(jkeljo): Get a java 8 stub to compile against and then uncomment the below:
  // @Override
  public void removeTaskListener(TaskListener taskListener) {
    // If check is just here to shut up the compiler about the unused parameter. Updating our tools
    // stub to java 8 will fix that too
    if (taskListener != null) {
      throw new UnsupportedOperationException("NYI");
    }
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

  List<TreeBackedTypeElement> enterTree(CompilationUnitTree compilationUnit) {
    try (BuckTracing.TraceSection t = BUCK_TRACING.traceSection("buck.abi.enterTree")) {
      return trees.enterTree(compilationUnit, resolverFactory);
    }
  }

  @Override
  public void setProcessors(Iterable<? extends Processor> processors) {
    if (processors.iterator().hasNext()) {
      // Only throw if there's actually something there; an empty list we can actually handle
      throw new UnsupportedOperationException("NYI");
    }
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
