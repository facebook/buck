// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

public abstract class Base3Format extends Instruction {

  public static final int SIZE = 3;

  protected Base3Format() {}

  public Base3Format(BytecodeStream stream) {
    super(stream);
  }

  @Override
  public int getSize() {
    return SIZE;
  }
}