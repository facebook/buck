// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

public class StackValue extends Value {

  public StackValue(ValueType type) {
    super(Value.UNDEFINED_NUMBER, type, null);
  }

  @Override
  public String toString() {
    return "s";
  }
}
