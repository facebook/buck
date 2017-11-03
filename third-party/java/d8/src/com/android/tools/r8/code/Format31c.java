// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import static com.android.tools.r8.dex.Constants.U8BIT_MAX;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;
import java.util.function.BiPredicate;

abstract class Format31c extends Base3Format {

  public final short AA;
  public DexString BBBBBBBB;

  // vAA | op | string@BBBBlo | string@#+BBBBhi
  Format31c(int high, BytecodeStream stream, DexString[] map) {
    super(stream);
    AA = (short) high;
    BBBBBBBB = map[(int) read32BitValue(stream)];
  }

  Format31c(int AA, DexString BBBBBBBB) {
    assert 0 <= AA && AA <= U8BIT_MAX;
    this.AA = (short) AA;
    this.BBBBBBBB = BBBBBBBB;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(AA, dest);
    write32BitReference(BBBBBBBB, dest, mapping);
  }

  @Override
  public final int hashCode() {
    return ((BBBBBBBB.hashCode() << 8) | AA) ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format31c o = (Format31c) other;
    return o.AA == AA && o.BBBBBBBB.equals(BBBBBBBB);
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString(
        "v" + AA + ", " + (naming == null ? BBBBBBBB : naming.originalNameOf(BBBBBBBB)));
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    BBBBBBBB.collectIndexedItems(indexedItems);
  }

  @Override
  public boolean equals(Instruction other, BiPredicate<IndexedDexItem, IndexedDexItem> equality) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format31c o = (Format31c) other;
    return o.AA == AA && equality.test(BBBBBBBB, o.BBBBBBBB);
  }
}
