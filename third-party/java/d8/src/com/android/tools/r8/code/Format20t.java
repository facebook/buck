// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;

abstract class Format20t extends Base2Format {

  public /* offset */ short AAAA;

  // øø | op | +AAAA
  Format20t(int high, BytecodeStream stream) {
    super(stream);
    AAAA = readSigned16BitValue(stream);
  }

  protected Format20t(int AAAA) {
    assert Short.MIN_VALUE <= AAAA && AAAA <= Short.MAX_VALUE;
    this.AAAA = (short) AAAA;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(0, dest);
    write16BitValue(AAAA, dest);
  }

  @Override
  public final int hashCode() {
    return AAAA ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || this.getClass() != other.getClass()) {
      return false;
    }
    return ((Format20t) other).AAAA == AAAA;
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("" + AAAA + " " + formatRelativeOffset(AAAA));
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString(":label_" + (getOffset() + AAAA));
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    // No references.
  }
}
