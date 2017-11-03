// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexProgramClass;
import java.nio.charset.StandardCharsets;

class UsagePrinter {
  private static final String INDENT = "    ";

  static final UsagePrinter DONT_PRINT = new NoOpUsagePrinter();

  private final StringBuilder writer;
  private DexProgramClass enclosingClazz = null;
  private boolean clazzPrefixPrinted = false;

  UsagePrinter() {
    writer = new StringBuilder();
  }

  byte[] toByteArray() {
    return writer.toString().getBytes(StandardCharsets.UTF_8);
  }

  void printUnusedClass(DexProgramClass clazz) {
    writer.append(clazz.toSourceString());
    writer.append('\n');
  }

  // Visiting methods and fields of the given clazz.
  void visiting(DexProgramClass clazz) {
    assert enclosingClazz == null;
    enclosingClazz = clazz;
  }

  // Visited methods and fields of the top at the clazz stack.
  void visited() {
    enclosingClazz = null;
    clazzPrefixPrinted = false;
  }

  private void printClazzPrefixIfNecessary() {
    assert enclosingClazz != null;
    if (!clazzPrefixPrinted) {
      writer.append(enclosingClazz.toSourceString());
      writer.append('\n');
      clazzPrefixPrinted = true;
    }
  }

  void printUnusedMethod(DexEncodedMethod method) {
    printClazzPrefixIfNecessary();
    writer.append(INDENT);
    String accessFlags = method.accessFlags.toString();
    if (!accessFlags.isEmpty()) {
      writer.append(accessFlags).append(' ');
    }
    writer.append(method.method.proto.returnType.toSourceString()).append(' ');
    writer.append(method.method.name.toSourceString());
    writer.append('(');
    for (int i = 0; i < method.method.proto.parameters.values.length; i++) {
      if (i != 0) {
        writer.append(',');
      }
      writer.append(method.method.proto.parameters.values[i].toSourceString());
    }
    writer.append(')');
    writer.append('\n');
  }

  void printUnusedField(DexEncodedField field) {
    printClazzPrefixIfNecessary();
    writer.append(INDENT);
    String accessFlags = field.accessFlags.toString();
    if (!accessFlags.isEmpty()) {
      writer.append(accessFlags).append(' ');
    }
    writer.append(field.field.type.toSourceString()).append(" ");
    writer.append(field.field.name.toSourceString());
    writer.append('\n');
  }

  // Empty implementation to silently ignore printing dead code.
  private static class NoOpUsagePrinter extends UsagePrinter {

    @Override
    byte[] toByteArray() {
      return null;
    }

    @Override
    void printUnusedClass(DexProgramClass clazz) {
      // Intentionally left empty.
    }

    @Override
    void visiting(DexProgramClass clazz) {
      // Intentionally left empty.
    }

    @Override
    void visited() {
      // Intentionally left empty.
    }

    @Override
    void printUnusedMethod(DexEncodedMethod method) {
      // Intentionally left empty.
    }

    @Override
    void printUnusedField(DexEncodedField field) {
      // Intentionally left empty.
    }
  }
}
