// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;
import java.util.function.BiPredicate;

abstract class Format21c extends Base2Format {

  public final short AA;
  public IndexedDexItem BBBB;

  // AA | op | [type|field|string]@BBBB
  Format21c(int high, BytecodeStream stream, IndexedDexItem[] map) {
    super(stream);
    AA = (short) high;
    BBBB = map[read16BitValue(stream)];
  }

  protected Format21c(int AA, IndexedDexItem BBBB) {
    assert 0 <= AA && AA <= Constants.U8BIT_MAX;
    this.AA = (short) AA;
    this.BBBB = BBBB;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(AA, dest);
    write16BitReference(BBBB, dest, mapping);
  }

  @Override
  public final int hashCode() {
    return ((BBBB.hashCode() << 8) | AA) ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || this.getClass() != other.getClass()) {
      return false;
    }
    Format21c o = (Format21c) other;
    return o.AA == AA && o.BBBB.equals(BBBB);
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString(
        "v" + AA + ", " + (naming == null ? BBBB.toString() : naming.originalNameOf(BBBB)));
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    // TODO(sgjesse): Add support for smali name mapping.
    return formatSmaliString("v" + AA + ", " + BBBB.toSmaliString());
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    BBBB.collectIndexedItems(indexedItems);
  }

  @Override
  public boolean equals(Instruction other, BiPredicate<IndexedDexItem, IndexedDexItem> equality) {
    if (other == null || this.getClass() != other.getClass()) {
      return false;
    }
    Format21c o = (Format21c) other;
    return o.AA == AA && equality.test(BBBB, o.BBBB);
  }
}
