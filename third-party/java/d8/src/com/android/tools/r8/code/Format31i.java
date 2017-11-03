// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;

abstract class Format31i extends Base3Format {

  public final short AA;
  public final int BBBBBBBB;

  // vAA | op | #+BBBBlo | #+BBBBhi
  /*package*/ Format31i(int high, BytecodeStream stream) {
    super(stream);
    AA = (short) high;
    BBBBBBBB = readSigned32BitValue(stream);
  }

  /*package*/ Format31i(int AA, int BBBBBBBB) {
    assert 0 <= AA && AA <= Constants.U8BIT_MAX;
    this.AA = (short) AA;
    this.BBBBBBBB = BBBBBBBB;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(AA, dest);
    write32BitValue(BBBBBBBB, dest);
  }

  @Override
  public final int hashCode() {
    return ((BBBBBBBB << 8) | AA) ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format31i o = (Format31i) other;
    return o.AA == AA && o.BBBBBBBB == BBBBBBBB;
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", #" + BBBBBBBB);
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    // No references.
  }
}
