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
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.processing.Processor;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleElementVisitor8;
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
class FrontendOnlyJavacTask extends JavacTask {
  private static final BuckTracing BUCK_TRACING = BuckTracing.getInstance("TreeResolver");
  private final JavacTask javacTask;
  private final TreeBackedElements elements;
  private final TreeBackedTrees trees;
  private final TreeBackedTypes types;
  private final TypeResolverFactory resolverFactory;

  @Nullable
  private Iterable<? extends CompilationUnitTree> parsedCompilationUnits;

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

  /**
   * This implementation of analyze just does what javac calls "enter" (resolving and entering the
   * symbols in the interface of each class into the symbol tables). javac's implementation also
   * goes into the bodies of methods and anonymous classes. That is not needed for this class for
   * its current intended uses.
   */
  @Override
  public Iterable<? extends Element> analyze() throws IOException {
    List<Element> foundElements = StreamSupport.stream(parse().spliterator(), false)
        .map(this::enterTree)
        .flatMap(List::stream)
        .collect(Collectors.toList());

    foundElements.forEach(element -> element.accept(new SimpleElementVisitor8<Void, Void>() {
      @Override
      public Void visitType(TypeElement e, Void aVoid) {
        ((TreeBackedTypeElement) e).resolve();
        return null;
      }
    }, null));

    return foundElements;
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

  List<Element> enterTree(CompilationUnitTree compilationUnit) {
    List<Element> topLevelElements = new ArrayList<>();

    try (BuckTracing.TraceSection t = BUCK_TRACING.traceSection("buck.abi.enterTree")) {
      new TreePathScanner<Void, Void>() {
        TreeBackedScope enclosingScope = trees.getScope(trees.getPath(
            compilationUnit,
            compilationUnit));

        @Override
        public Void visitCompilationUnit(CompilationUnitTree node, Void aVoid) {
          Path sourcePath = Paths.get(node.getSourceFile().getName());
          if (sourcePath.getFileName().toString().equals("package-info.java")) {
            PackageElement packageElement = elements.getOrCreatePackageElement(
                TreeBackedTrees.treeToName(compilationUnit.getPackageName()));

            topLevelElements.add(packageElement);
          }

          return super.visitCompilationUnit(node, aVoid);
        }

        @Override
        public Void visitClass(ClassTree node, Void aVoid) {
          // Match javac: create a package element only once we know a class exists in it
          elements.getOrCreatePackageElement(
              TreeBackedTrees.treeToName(compilationUnit.getPackageName()));

          Name qualifiedName = enclosingScope.buildQualifiedName(node.getSimpleName());

          TreeBackedScope classScope = trees.getScope(getCurrentPath());
          TreeBackedTypeElement typeElement =
              new TreeBackedTypeElement(
                  enclosingScope.getEnclosingElement(),
                  node,
                  qualifiedName,
                  resolverFactory);

          if (enclosingScope.getEnclosingClass() == null) {
            topLevelElements.add(typeElement);
          }
          elements.enterTypeElement(typeElement);
          trees.enterElement(getCurrentPath(), typeElement);

          TreeBackedScope oldScope = enclosingScope;
          enclosingScope = classScope;
          try {
            return super.visitClass(node, aVoid);
          } finally {
            enclosingScope = oldScope;
          }
        }

        @Override
        public Void visitTypeParameter(TypeParameterTree node, Void aVoid) {
          TreeBackedTypeElement enclosingClass =
              Preconditions.checkNotNull(enclosingScope.getEnclosingClass());

          TreeBackedTypeParameterElement typeParameter = new TreeBackedTypeParameterElement(
              node,
              enclosingClass,
              resolverFactory);
          enclosingClass.addTypeParameter(typeParameter);
          trees.enterElement(getCurrentPath(), typeParameter);

          return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Void aVoid) {
          // TODO(jkeljo): Construct an ExecutableElement

          // The body of a method is not part of the ABI, so don't recurse into them
          return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void aVoid) {
          // TODO(jkeljo): Construct a VariableElement
          // TODO(jkeljo): Evaluate constants

          // Except for constants, we shouldn't look at the next part of a variable decl, because
          // there might be anonymous classes there and those are not part of the ABI
          return null;
        }
      }.scan(compilationUnit, null);
    }

    return topLevelElements;
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
