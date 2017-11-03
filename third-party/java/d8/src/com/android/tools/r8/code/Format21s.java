// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.StringUtils;
import java.nio.ShortBuffer;

abstract class Format21s extends Base2Format {

  public final short AA;
  public final short BBBB;

  // AA | op | #+BBBB
  /*package*/ Format21s(int high, BytecodeStream stream) {
    super(stream);
    AA = (short) high;
    BBBB = readSigned16BitValue(stream);
  }

  /*package*/ Format21s(int AA, int BBBB) {
    assert Short.MIN_VALUE <= BBBB && BBBB <= Short.MAX_VALUE;
    assert 0 <= AA && AA <= Constants.U8BIT_MAX;
    this.AA = (short) AA;
    this.BBBB = (short) BBBB;
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
    Format21s o = (Format21s) other;
    return o.AA == AA && o.BBBB == BBBB;
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", #" + BBBB);
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + AA + ", " + StringUtils.hexString(BBBB, 4) + "  # " + BBBB);
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    // No references.
  }
}
