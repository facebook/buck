// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import java.util.HashSet;
import java.util.Set;

/**
 * Implements traversal of the class hierarchy in topological order. A class is visited after its
 * super class and its interfaces are visited. Only visits program classes and does NOT visit 
 * classpath, nor library classes.
 *
 * NOTE: The visiting is processed by traversing program classes only, which means that
 * in presence of classpath it is NOT guaranteed that class C is visited before class D
 * if there exists a classpath class X in class hierarchy between C and D, like:
 *
 * <pre>
 *   class ProgramClassS {}
 *   class ClasspathClassX extends ProgramClassS {}
 *   class ProgramClassD extends ClasspathClassX {}
 * </pre>
 *
 * The above consideration does not apply to library classes, since we assume library
 * classes never extend or implement program/classpath class.
 */
public abstract class ProgramClassVisitor {

  final DexApplication application;
  private final Set<DexItem> visited = new HashSet<>();

  protected ProgramClassVisitor(DexApplication application) {
    this.application = application;
  }

  private void accept(DexType type) {
    if (type == null || visited.contains(type)) {
      return;
    }
    DexClass clazz = application.programDefinitionFor(type);
    if (clazz != null) {
      accept(clazz);
      return;
    }
    visit(type);
    visited.add(type);
  }

  private void accept(DexTypeList types) {
    for (DexType type : types.values) {
      accept(type);
    }
  }

  private void accept(DexClass clazz) {
    if (visited.contains(clazz)) {
      return;
    }
    accept(clazz.superType);
    accept(clazz.interfaces);
    visit(clazz);
    visited.add(clazz);
  }

  public void run(DexProgramClass[] classes) {
    for (DexProgramClass clazz : classes) {
      accept(clazz);
    }
  }

  public void run(Iterable<DexProgramClass> classes) {
    for (DexProgramClass clazz : classes) {
      accept(clazz);
    }
  }

  public void run() {
    run(application.classes());
  }

  /**
   * Called for each library class used in the class hierarchy. A library class is a class that is
   * not present in the application.
   */
  public abstract void visit(DexType type);

  /**
   * Called for each class defined in the application.
   */
  public abstract void visit(DexClass clazz);
}
