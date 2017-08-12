// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;

abstract class Format12x extends Base1Format {

  public final byte A, B;

  // vB | vA | op
  Format12x(int high, BytecodeStream stream) {
    super(stream);
    A = (byte) (high & 0xF);
    B = (byte) ((high >> 4) & 0xF);
  }

  Format12x(int A, int B) {
    assert 0 <= A && A <= Constants.U4BIT_MAX;
    assert 0 <= B && B <= Constants.U4BIT_MAX;
    this.A = (byte) A;
    this.B = (byte) B;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(B, A, dest);
  }

  @Override
  public final int hashCode() {
    return ((A << 4) | B) ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || this.getClass() != other.getClass()) {
      return false;
    }
    Format12x o = (Format12x) other;
    return o.A == A && o.B == B;
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("v" + A + ", v" + B);
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + A + ", v" + B);
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    // No references.
  }
}
