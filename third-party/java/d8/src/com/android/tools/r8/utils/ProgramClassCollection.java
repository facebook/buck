// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.ClassKind;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.desugar.LambdaRewriter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Represents a collection of library classes. */
public class ProgramClassCollection extends ClassMap<DexProgramClass> {
  public static ProgramClassCollection create(List<DexProgramClass> classes) {
    // We have all classes preloaded, but not necessarily without conflicts.
    IdentityHashMap<DexType, Supplier<DexProgramClass>> map = new IdentityHashMap<>();
    for (DexProgramClass clazz : classes) {
      map.merge(clazz.type, clazz, (a, b) -> resolveClassConflictImpl(a.get(), b.get()));
    }
    return new ProgramClassCollection(map);
  }

  private ProgramClassCollection(IdentityHashMap<DexType, Supplier<DexProgramClass>> classes) {
    super(classes, null);
  }

  @Override
  public String toString() {
    return "program classes: " + super.toString();
  }

  @Override
  DexProgramClass resolveClassConflict(DexProgramClass a, DexProgramClass b) {
    return resolveClassConflictImpl(a, b);
  }

  @Override
  Supplier<DexProgramClass> getTransparentSupplier(DexProgramClass clazz) {
    return clazz;
  }

  @Override
  ClassKind getClassKind() {
    return ClassKind.PROGRAM;
  }

  private static DexProgramClass resolveClassConflictImpl(DexProgramClass a, DexProgramClass b) {
    // Currently only allow collapsing synthetic lambda classes.
    if (a.originatesFromDexResource()
        && b.originatesFromDexResource()
        && a.accessFlags.isSynthetic()
        && b.accessFlags.isSynthetic()
        && LambdaRewriter.hasLambdaClassPrefix(a.type)
        && LambdaRewriter.hasLambdaClassPrefix(b.type)) {
      return a;
    }
    throw new CompilationError("Program type already present: " + a.type.toSourceString());
  }
}
