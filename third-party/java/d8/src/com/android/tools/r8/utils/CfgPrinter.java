// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import java.util.Stack;

/**
 * Printer abstraction for creating flow-graphs for the C1 visualizer.
 */
public class CfgPrinter {

  private final StringBuilder builder = new StringBuilder();
  private final Stack<String> opened = new Stack<>();
  private final int indentSpacing = 2;

  public int nextUnusedValue = 0;

  public String makeUnusedValue() {
    return "_" + nextUnusedValue++;
  }

  public void resetUnusedValue() {
    nextUnusedValue = 0;
  }

  public CfgPrinter begin(String title) {
    print("begin_");
    append(title).ln();
    opened.push(title);
    return this;
  }

  public CfgPrinter end(String title) {
    String top = opened.pop();
    assert title.equals(top);
    print("end_");
    append(title).ln();
    return this;
  }

  public CfgPrinter print(int i) {
    printIndent();
    builder.append(i);
    return this;
  }

  public CfgPrinter print(String string) {
    printIndent();
    builder.append(string);
    return this;
  }

  public CfgPrinter append(int i) {
    builder.append(i);
    return this;
  }

  public CfgPrinter append(String string) {
    builder.append(string);
    return this;
  }

  public CfgPrinter sp() {
    builder.append(" ");
    return this;
  }

  public CfgPrinter ln() {
    builder.append("\n");
    return this;
  }

  private void printIndent() {
    for (int i = 0; i < opened.size() * indentSpacing; i++) {
      builder.append(" ");
    }
  }

  @Override
  public String toString() {
    return builder.toString();
  }
}
