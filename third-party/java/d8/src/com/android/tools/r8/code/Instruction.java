// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.InternalCompilerError;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.StringUtils;
import java.nio.ShortBuffer;
import java.util.function.BiPredicate;

public abstract class Instruction {

  public final static int[] NO_TARGETS = null;
  public final static int[] EXIT_TARGET = new int[]{};

  public int offset;

  Instruction(BytecodeStream stream) {
    // When this constructor is invoked, we have already read 1 ushort from the stream.
    this.offset = stream.getOffset() - 1;
  }

  protected Instruction() {
    this.offset = -1;
  }

  static byte readSigned8BitValue(BytecodeStream stream) {
    return (byte) stream.nextByte();
  }

  static short read8BitValue(BytecodeStream stream) {
    return (short) stream.nextByte();
  }

  static short readSigned16BitValue(BytecodeStream stream) {
    // Convert to signed.
    return (short) stream.nextShort();
  }

  static char read16BitValue(BytecodeStream stream) {
    return (char) (stream.nextShort() & 0xffff);
  }

  static int readSigned32BitValue(BytecodeStream stream) {
    int low = read16BitValue(stream);
    int high = read16BitValue(stream);
    int result = ((high << 16) & 0xffff0000) | (low & 0xffff);
    return result;
  }

  static long read32BitValue(BytecodeStream stream) {
    long low = read16BitValue(stream);
    long high = read16BitValue(stream);
    long result = ((high & 0xffff) << 16) | (low & 0xffff);
    return result;
  }

  static long read64BitValue(BytecodeStream stream) {
    long low = read32BitValue(stream);
    long high = read32BitValue(stream);
    long result = (high << 32) | low;
    return result;
  }

  protected static short combineBytes(int high, int low) {
    return (short) (((high & 0xff) << 8) | (low & 0xff));
  }

  protected static int makeByte(int high, int low) {
    return ((high & 0xf) << 4) | (low & 0xf);
  }

  protected void writeFirst(int aa, ShortBuffer dest) {
    dest.put((short) (((aa & 0xff) << 8) | (getOpcode() & 0xff)));
  }

  protected void writeFirst(int a, int b, ShortBuffer dest) {
    dest.put((short) (((a & 0xf) << 12) | ((b & 0xf) << 8) | (getOpcode() & 0xff)));
  }

  protected void write16BitValue(int value, ShortBuffer dest) {
    dest.put((short) value);
  }

  protected void write32BitValue(long value, ShortBuffer dest) {
    dest.put((short) (value & 0xffff));
    dest.put((short) ((value >> 16) & 0xffff));
  }

  protected void write64BitValue(long value, ShortBuffer dest) {
    write32BitValue(value & 0xffffffff, dest);
    write32BitValue((value >> 32) & 0xffffffff, dest);
  }

  protected void write16BitReference(IndexedDexItem item, ShortBuffer dest,
      ObjectToOffsetMapping mapping) {
    int index = item.getOffset(mapping);
    assert index == (index & 0xffff);
    write16BitValue(index, dest);
  }

  protected void write32BitReference(IndexedDexItem item, ShortBuffer dest,
      ObjectToOffsetMapping mapping) {
    write32BitValue(item.getOffset(mapping), dest);
  }

  public int getOffset() {
    return offset;
  }

  public void setOffset(int offset) {
    this.offset = offset;
  }

  public boolean isPayload() {
    return false;
  }

  public boolean isSwitchPayload() {
    return false;
  }

  public boolean hasPayload() {
    return false;
  }

  public boolean isSwitch() {
    return false;
  }

  public int getPayloadOffset() {
    return 0;
  }

  static String formatOffset(int offset) {
    return StringUtils.hexString(offset, 2);
  }

  static String formatDecimalOffset(int offset) {
    return offset >= 0 ? ("+" + offset) : Integer.toString(offset);
  }

  String formatRelativeOffset(int offset) {
    return formatOffset(getOffset() + offset) + " (" + formatDecimalOffset(offset) + ")";
  }

  String formatString(String left) {
    StringBuilder builder = new StringBuilder();
    StringUtils.appendLeftPadded(builder, formatOffset(getOffset()), 6);
    builder.append(": ");
    StringUtils.appendRightPadded(builder, getName(), 20);
    builder.append(left == null ? "" : left);
    return builder.toString();
  }

  String formatSmaliString(String left) {
    StringBuilder builder = new StringBuilder();
    builder.append("    ");
    if (left != null) {
      StringUtils.appendRightPadded(builder, getSmaliName(), 20);
      builder.append(left);
    } else {
      builder.append(getSmaliName());
    }
    return builder.toString();
  }

  public int[] getTargets() {
    return NO_TARGETS;
  }

  public abstract void buildIR(IRBuilder builder) throws ApiLevelException;

  public DexCallSite getCallSite() {
    return null;
  }

  public DexMethod getMethod() {
    return null;
  }

  public DexProto getProto() {
    return null;
  }

  public DexField getField() {
    return null;
  }

  @Override
  public abstract boolean equals(Object obj);

  @Override
  public abstract int hashCode();

  public abstract String getName();

  public abstract String getSmaliName();

  public abstract int getOpcode();

  public abstract int getSize();

  public String toSmaliString(Instruction payloadUser) {
    throw new InternalCompilerError("Instruction " + payloadUser + " is not a payload user");
  }

  public abstract String toSmaliString(ClassNameMapper naming);

  public String toSmaliString() {
    return toSmaliString((ClassNameMapper) null);
  }

  public abstract String toString(ClassNameMapper naming);

  public String toString(ClassNameMapper naming, Instruction payloadUser) {
    throw new InternalCompilerError("Instruction " + payloadUser + " is not a payload user");
  }

  @Override
  public String toString() {
    return toString(null);
  }

  public abstract void write(ShortBuffer buffer, ObjectToOffsetMapping mapping);

  public abstract void collectIndexedItems(IndexedItemCollection indexedItems);

  public boolean equals(Instruction other, BiPredicate<IndexedDexItem, IndexedDexItem> equality) {
    // In the default case, there is nothing to substitute.
    return this.equals(other);
  }

  public void registerUse(UseRegistry registry) {
    // Intentionally empty
  }

  public boolean canThrow() {
    return false;
  }
}
