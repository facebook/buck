// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import static com.android.tools.r8.dex.Constants.U4BIT_MAX;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;

/** Format45cc for instructions of size 4, with 5 registers and 2 constant pool index. */
public abstract class Format45cc extends Base4Format {

  public final byte A;
  public final byte C;
  public final byte D;
  public final byte E;
  public final byte F;
  public final byte G;
  public DexMethod BBBB;
  public DexProto HHHH;

  Format45cc(int high, BytecodeStream stream, DexMethod[] methodMap, DexProto[] protoMap) {
    super(stream);
    G = (byte) (high & 0xf);
    A = (byte) ((high >> 4) & 0xf);
    BBBB = methodMap[read16BitValue(stream)];
    int next = read8BitValue(stream);
    E = (byte) (next & 0xf);
    F = (byte) ((next >> 4) & 0xf);
    next = read8BitValue(stream);
    C = (byte) (next & 0xf);
    D = (byte) ((next >> 4) & 0xf);
    HHHH = protoMap[read16BitValue(stream)];
  }

  // A | G | op | [meth]@BBBB | F | E | D | C | [proto]@HHHH
  protected Format45cc(int A, DexMethod BBBB, DexProto HHHH, int C, int D, int E, int F, int G) {
    assert 0 <= A && A <= U4BIT_MAX;
    assert 0 <= C && C <= U4BIT_MAX;
    assert 0 <= D && D <= U4BIT_MAX;
    assert 0 <= E && E <= U4BIT_MAX;
    assert 0 <= F && F <= U4BIT_MAX;
    assert 0 <= G && G <= U4BIT_MAX;
    this.A = (byte) A;
    this.BBBB = BBBB;
    this.HHHH = HHHH;
    this.C = (byte) C;
    this.D = (byte) D;
    this.E = (byte) E;
    this.F = (byte) F;
    this.G = (byte) G;
  }

  @Override
  public final int hashCode() {
    return ((HHHH.hashCode() << 28)
            | (BBBB.hashCode() << 24)
            | (A << 20)
            | (C << 16)
            | (D << 12)
            | (E << 8)
            | (F << 4)
            | G)
        ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format45cc o = (Format45cc) other;
    return o.A == A
        && o.C == C
        && o.D == D
        && o.E == E
        && o.F == F
        && o.G == G
        && o.BBBB.equals(BBBB)
        && o.HHHH.equals(HHHH);
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    BBBB.collectIndexedItems(indexedItems);
    HHHH.collectIndexedItems(indexedItems);
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(A, G, dest);
    write16BitReference(BBBB, dest, mapping);
    write16BitValue(combineBytes(makeByte(F, E), makeByte(D, C)), dest);
    write16BitReference(HHHH, dest, mapping);
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    appendRegisterArguments(builder, ", ");
    builder.append(", ");
    // TODO(sgjesse): Add support for smali name mapping.
    builder.append(BBBB.toSmaliString());
    builder.append(", ");
    builder.append(HHHH.toSmaliString());
    return formatSmaliString(builder.toString());
  }

  @Override
  public String toString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    appendRegisterArguments(builder, " ");
    builder.append(" ");
    builder.append(itemToString(BBBB, naming));
    builder.append(", ");
    builder.append(itemToString(HHHH, naming));
    return formatString(builder.toString());
  }

  private String itemToString(IndexedDexItem indexedDexItem, ClassNameMapper naming) {
    String str;
    if (naming == null) {
      str = indexedDexItem.toSmaliString();
    } else {
      str = naming.originalNameOf(indexedDexItem);
    }
    return str;
  }

  private void appendRegisterArguments(StringBuilder builder, String separator) {
    builder.append("{ ");
    int[] values = new int[] {C, D, E, F, G};
    for (int i = 0; i < A; i++) {
      if (i != 0) {
        builder.append(separator);
      }
      builder.append("v").append(values[i]);
    }
    builder.append(" }");
  }

  @Override
  public DexMethod getMethod() {
    return BBBB;
  }

  @Override
  public DexProto getProto() {
    return HHHH;
  }
}
