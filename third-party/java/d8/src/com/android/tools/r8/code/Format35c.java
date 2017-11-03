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

public abstract class Format35c extends Base3Format {

  public final byte A;
  public final byte C;
  public final byte D;
  public final byte E;
  public final byte F;
  public final byte G;
  public IndexedDexItem BBBB;

  // A | G | op | BBBB | F | E | D | C
  Format35c(int high, BytecodeStream stream, IndexedDexItem[] map) {
    super(stream);
    G = (byte) (high & 0xf);
    A = (byte) ((high >> 4) & 0xf);
    BBBB = map[read16BitValue(stream)];
    int next = read8BitValue(stream);
    E = (byte) (next & 0xf);
    F = (byte) ((next >> 4) & 0xf);
    next = read8BitValue(stream);
    C = (byte) (next & 0xf);
    D = (byte) ((next >> 4) & 0xf);
  }

  protected Format35c(int A, IndexedDexItem BBBB, int C, int D, int E, int F, int G) {
    assert 0 <= A && A <= Constants.U4BIT_MAX;
    assert 0 <= C && C <= Constants.U4BIT_MAX;
    assert 0 <= D && D <= Constants.U4BIT_MAX;
    assert 0 <= E && E <= Constants.U4BIT_MAX;
    assert 0 <= F && F <= Constants.U4BIT_MAX;
    assert 0 <= G && G <= Constants.U4BIT_MAX;
    this.A = (byte) A;
    this.BBBB = BBBB;
    this.C = (byte) C;
    this.D = (byte) D;
    this.E = (byte) E;
    this.F = (byte) F;
    this.G = (byte) G;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(A, G, dest);
    write16BitReference(BBBB, dest, mapping);
    write16BitValue(combineBytes(makeByte(F, E), makeByte(D, C)), dest);
  }

  @Override
  public final int hashCode() {
    return ((BBBB.hashCode() << 24) | (A << 20) | (C << 16) | (D << 12) | (E << 8) | (F << 4)
        | G) ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format35c o = (Format35c) other;
    return o.A == A && o.C == C && o.D == D && o.E == E && o.F == F && o.G == G
        && o.BBBB.equals(BBBB);
  }

  private void appendRegisterArguments(StringBuilder builder, String separator) {
    builder.append("{ ");
    int[] values = new int[]{C, D, E, F, G};
    for (int i = 0; i < A; i++) {
      if (i != 0) {
        builder.append(separator);
      }
      builder.append("v").append(values[i]);
    }
    builder.append(" }");
  }

  @Override
  public String toString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    appendRegisterArguments(builder, " ");
    builder.append(" ");
    if (naming == null) {
      builder.append(BBBB.toSmaliString());
    } else {
      builder.append(naming.originalNameOf(BBBB));
    }
    return formatString(builder.toString());
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    appendRegisterArguments(builder, ", ");
    builder.append(", ");
    // TODO(sgjesse): Add support for smali name mapping.
    builder.append(BBBB.toSmaliString());
    return formatSmaliString(builder.toString());
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    BBBB.collectIndexedItems(indexedItems);
  }

  @Override
  public boolean equals(Instruction other, BiPredicate<IndexedDexItem, IndexedDexItem> equality) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format35c o = (Format35c) other;
    return o.A == A && o.C == C && o.D == D && o.E == E && o.F == F && o.G == G
        && equality.test(BBBB, o.BBBB);
  }
}
