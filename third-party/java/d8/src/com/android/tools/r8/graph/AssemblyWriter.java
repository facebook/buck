// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.InternalOptions;
import java.io.PrintStream;

public class AssemblyWriter extends DexByteCodeWriter {

  public AssemblyWriter(DexApplication application, InternalOptions options) {
    super(application, options);
  }

  @Override
  String getFileEnding() {
    return ".dump";
  }

  @Override
  void writeClassHeader(DexProgramClass clazz, PrintStream ps) {
    String clazzName;
    if (application.getProguardMap() != null) {
      clazzName = application.getProguardMap().originalNameOf(clazz.type);
    } else {
      clazzName = clazz.type.toSourceString();
    }
    ps.println("# Bytecode for");
    ps.println("# Class: '" + clazzName + "'");
    ps.println();
  }

  @Override
  void writeField(DexEncodedField field, PrintStream ps) {
    // Not implemented, yet.
  }

  @Override
  void writeMethod(DexEncodedMethod method, PrintStream ps) {
    ClassNameMapper naming = application.getProguardMap();
    String methodName = naming != null
        ? naming.originalSignatureOf(method.method).toString()
        : method.method.name.toString();
    ps.println("#");
    ps.println("# Method: '" + methodName + "':");
    ps.println("#");
    ps.println();
    Code code = method.getCode();
    if (code != null) {
      ps.println(code.toString(method, naming));
    }
  }

  @Override
  void writeClassFooter(DexProgramClass clazz, PrintStream ps) {

  }
}
