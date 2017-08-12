// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;
import java.util.function.BiPredicate;

/** Format4rcc for instructions of size 4, with a range of registers and 2 constant pool index. */
public abstract class Format4rcc extends Base4Format {

  public final short AA;
  public final char CCCC;
  public DexMethod BBBB;
  public DexProto HHHH;

  // AA | op | [meth]@BBBB | CCCC | [proto]@HHHH
  Format4rcc(int high, BytecodeStream stream, DexMethod[] methodMap, DexProto[] protoMap) {
    super(stream);
    this.AA = (short) high;
    this.BBBB = methodMap[read16BitValue(stream)];
    this.CCCC = read16BitValue(stream);
    this.HHHH = protoMap[read16BitValue(stream)];
  }

  Format4rcc(int firstArgumentRegister, int argumentCount, DexMethod method, DexProto proto) {
    assert 0 <= firstArgumentRegister && firstArgumentRegister <= Constants.U16BIT_MAX;
    assert 0 <= argumentCount && argumentCount <= Constants.U8BIT_MAX;
    this.CCCC = (char) firstArgumentRegister;
    this.AA = (short) argumentCount;
    BBBB = method;
    HHHH = proto;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(AA, dest);
    write16BitReference(BBBB, dest, mapping);
    write16BitValue(CCCC, dest);
    write16BitReference(HHHH, dest, mapping);
  }

  @Override
  public final int hashCode() {
    return ((CCCC << 24) | (HHHH.hashCode() << 12) | (BBBB.hashCode() << 4) | AA)
        ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format4rcc o = (Format4rcc) other;
    return o.AA == AA && o.CCCC == CCCC && o.BBBB.equals(BBBB) && o.HHHH.equals(HHHH);
  }

  @Override
  public String toString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    appendRegisterRange(builder);
    builder.append(" ");
    if (naming == null) {
      builder.append(BBBB.toSmaliString());
    } else {
      builder.append(naming.originalNameOf(BBBB));
    }
    if (naming == null) {
      builder.append(HHHH.toSmaliString());
    } else {
      builder.append(naming.originalNameOf(HHHH));
    }
    return formatString(builder.toString());
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    appendRegisterRange(builder);
    builder.append(", ");
    // TODO(sgjesse): Add support for smali name mapping.
    builder.append(BBBB.toSmaliString());
    builder.append(", ");
    builder.append(HHHH.toSmaliString());
    return formatSmaliString(builder.toString());
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    BBBB.collectIndexedItems(indexedItems);
    HHHH.collectIndexedItems(indexedItems);
  }

  @Override
  public boolean equals(Instruction other, BiPredicate<IndexedDexItem, IndexedDexItem> equality) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format4rcc o = (Format4rcc) other;
    return o.AA == AA
        && o.CCCC == CCCC
        && equality.test(BBBB, o.BBBB)
        && equality.test(HHHH, o.HHHH);
  }

  private void appendRegisterRange(StringBuilder builder) {
    int firstRegister = CCCC;
    builder.append("{ ");
    builder.append("v").append(firstRegister);
    if (AA != 1) {
      builder.append(" .. v").append(firstRegister + AA - 1);
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
