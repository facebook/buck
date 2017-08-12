// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;

abstract class Format10t extends Base1Format {

  public /* offset */ byte AA;

  // +AA | op
  Format10t(int high, BytecodeStream stream) {
    super(stream);
    // AA is an offset, so convert to signed.
    AA = (byte) high;
  }

  protected Format10t(int AA) {
    assert Byte.MIN_VALUE <= AA && AA <= Byte.MAX_VALUE;
    this.AA = (byte) AA;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(AA, dest);
  }

  @Override
  public final int hashCode() {
    return AA ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    return ((Format10t) other).AA == AA;
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString(formatRelativeOffset(AA));
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString(":label_" + (getOffset() + AA));
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    // No references.
  }
}
