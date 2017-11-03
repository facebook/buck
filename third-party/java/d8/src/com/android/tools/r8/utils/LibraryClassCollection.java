// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.graph.ClassKind;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.logging.Log;
import java.util.function.Supplier;

/** Represents a collection of library classes. */
public class LibraryClassCollection extends ClassMap<DexLibraryClass> {
  public LibraryClassCollection(ClassProvider<DexLibraryClass> classProvider) {
    super(null, classProvider);
  }

  @Override
  DexLibraryClass resolveClassConflict(DexLibraryClass a, DexLibraryClass b) {
    if (Log.ENABLED) {
      Log.warn(DexApplication.class,
          "Class `%s` was specified twice as a library type.", a.type.toSourceString());
    }
    return a;
  }

  @Override
  Supplier<DexLibraryClass> getTransparentSupplier(DexLibraryClass clazz) {
    return clazz;
  }

  @Override
  ClassKind getClassKind() {
    return ClassKind.LIBRARY;
  }

  @Override
  public String toString() {
    return "library classes: " + super.toString();
  }
}
