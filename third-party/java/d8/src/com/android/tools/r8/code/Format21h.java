// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import java.nio.ShortBuffer;

abstract class Format21h extends Base2Format {

  public final short AA;
  public final char BBBB;

  // AA | op | BBBB0000[00000000]
  /*package*/ Format21h(int high, BytecodeStream stream) {
    super(stream);
    AA = (short) high;
    BBBB = read16BitValue(stream);
  }

  /*package*/ Format21h(int AA, int BBBB) {
    assert 0 <= AA && AA <= Constants.U8BIT_MAX;
    assert 0 <= BBBB && BBBB <= Constants.U16BIT_MAX;
    this.AA = (short) AA;
    this.BBBB = (char) BBBB;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(AA, dest);
    write16BitValue(BBBB, dest);
  }

  @Override
  public final int hashCode() {
    return ((BBBB << 8) | AA) ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || this.getClass() != other.getClass()) {
      return false;
    }
    Format21h o = (Format21h) other;
    return o.AA == AA && o.BBBB == BBBB;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    // No references.
  }
}
