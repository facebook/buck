// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.DexOutputBuffer;
import com.android.tools.r8.dex.FileWriter;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.utils.EncodedValueUtils;
import java.util.Arrays;

public abstract class DexValue extends DexItem {

  public static final UnknownDexValue UNKNOWN = UnknownDexValue.UNKNOWN;

  public static final byte VALUE_BYTE = 0x00;
  public static final byte VALUE_SHORT = 0x02;
  public static final byte VALUE_CHAR = 0x03;
  public static final byte VALUE_INT = 0x04;
  public static final byte VALUE_LONG = 0x06;
  public static final byte VALUE_FLOAT = 0x10;
  public static final byte VALUE_DOUBLE = 0x11;
  public static final byte VALUE_METHOD_TYPE = 0x15;
  public static final byte VALUE_METHOD_HANDLE = 0x16;
  public static final byte VALUE_STRING = 0x17;
  public static final byte VALUE_TYPE = 0x18;
  public static final byte VALUE_FIELD = 0x19;
  public static final byte VALUE_METHOD = 0x1a;
  public static final byte VALUE_ENUM = 0x1b;
  public static final byte VALUE_ARRAY = 0x1c;
  public static final byte VALUE_ANNOTATION = 0x1d;
  public static final byte VALUE_NULL = 0x1e;
  public static final byte VALUE_BOOLEAN = 0x1f;

  private static void writeHeader(byte type, int arg, DexOutputBuffer dest) {
    dest.putByte((byte) ((arg << 5) | type));
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    // Should never be visited.
    throw new Unreachable();
  }

  public abstract void sort();

  public abstract void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping);

  @Override
  public abstract int hashCode();

  @Override
  public abstract boolean equals(Object other);

  @Override
  public abstract String toString();

  public static DexValue defaultForType(DexType type, DexItemFactory factory) {
    if (type == factory.booleanType) {
      return DexValueBoolean.DEFAULT;
    }
    if (type == factory.byteType) {
      return DexValueByte.DEFAULT;
    }
    if (type == factory.charType) {
      return DexValueChar.DEFAULT;
    }
    if (type == factory.shortType) {
      return DexValueShort.DEFAULT;
    }
    if (type == factory.intType) {
      return DexValueInt.DEFAULT;
    }
    if (type == factory.longType) {
      return DexValueLong.DEFAULT;
    }
    if (type == factory.floatType) {
      return DexValueFloat.DEFAULT;
    }
    if (type == factory.doubleType) {
      return DexValueDouble.DEFAULT;
    }
    if (type.isArrayType() || type.isClassType()) {
      return DexValueNull.NULL;
    }
    throw new Unreachable("No default value for unexpected type " + type);
  }

  // Returns a const instruction for the non default value.
  public Instruction asConstInstruction(boolean hasClassInitializer, Value dest) {
    return null;
  }

  public boolean isDefault(DexType type, DexItemFactory factory) {
    return this == defaultForType(type, factory);
  }

  /**
   * Whether creating this value as a default value for a field might trigger an allocation.
   * <p>
   * This is conservative.
   */
  public boolean mayTriggerAllocation() {
    return true;
  }

  static public class UnknownDexValue extends DexValue {

    // Singleton instance.
    public static final UnknownDexValue UNKNOWN = new UnknownDexValue();

    private UnknownDexValue() {
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      throw new Unreachable();
    }

    @Override
    public void sort() {
      throw new Unreachable();
    }

    @Override
    public boolean mayTriggerAllocation() {
      return true;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      throw new Unreachable();
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object other) {
      return other == this;
    }

    @Override
    public String toString() {
      return "UNKNOWN";
    }

    @Override
    public Instruction asConstInstruction(boolean hasClassInitializer, Value dest) {
      return null;
    }
  }

  static private abstract class SimpleDexValue extends DexValue {

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      // Intentionally left empty
    }

    @Override
    public void sort() {
      // Intentionally empty
    }

    @Override
    public boolean mayTriggerAllocation() {
      return false;
    }

    protected static void writeIntegerTo(byte type, long value, int expected,
        DexOutputBuffer dest) {
      // Leave space for header.
      dest.forward(1);
      int length = dest.putSignedEncodedValue(value, expected);
      dest.rewind(length + 1);
      writeHeader(type, length - 1, dest);
      dest.forward(length);
    }

  }

  static public class DexValueByte extends SimpleDexValue {

    public static final DexValueByte DEFAULT = new DexValueByte((byte) 0);

    final byte value;

    private DexValueByte(byte value) {
      this.value = value;
    }

    public static DexValueByte create(byte value) {
      return value == DEFAULT.value ? DEFAULT : new DexValueByte(value);
    }

    public byte getValue() {
      return value;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      writeHeader(VALUE_BYTE, 0, dest);
      dest.putSignedEncodedValue(value, 1);
    }

    @Override
    public int hashCode() {
      return value * 3;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return other instanceof DexValueByte && value == ((DexValueByte) other).value;
    }

    @Override
    public String toString() {
      return "Byte " + value;
    }

    @Override
    public Instruction asConstInstruction(boolean hasClassInitializer, Value dest) {
      return (this == DEFAULT && hasClassInitializer) ? null : new ConstNumber(dest, value);
    }
  }

  static public class DexValueShort extends SimpleDexValue {

    public static final DexValueShort DEFAULT = new DexValueShort((short) 0);
    final short value;

    private DexValueShort(short value) {
      this.value = value;
    }

    public static DexValueShort create(short value) {
      return value == DEFAULT.value ? DEFAULT : new DexValueShort(value);
    }

    public short getValue() {
      return value;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      writeIntegerTo(VALUE_SHORT, value, Short.BYTES, dest);
    }

    @Override
    public int hashCode() {
      return value * 7;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return other instanceof DexValueShort && value == ((DexValueShort) other).value;
    }

    @Override
    public String toString() {
      return "Short " + value;
    }

    @Override
    public Instruction asConstInstruction(boolean hasClassInitializer, Value dest) {
      return (this == DEFAULT && hasClassInitializer) ? null : new ConstNumber(dest, value);
    }
  }

  static public class DexValueChar extends SimpleDexValue {

    public static final DexValueChar DEFAULT = new DexValueChar((char) 0);
    final char value;

    private DexValueChar(char value) {
      this.value = value;
    }

    public static DexValueChar create(char value) {
      return value == DEFAULT.value ? DEFAULT : new DexValueChar(value);
    }

    public char getValue() {
      return value;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      dest.forward(1);
      int length = dest.putUnsignedEncodedValue(value, 2);
      dest.rewind(length + 1);
      writeHeader(VALUE_CHAR, length - 1, dest);
      dest.forward(length);
    }

    @Override
    public int hashCode() {
      return value * 5;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return other instanceof DexValueChar && value == ((DexValueChar) other).value;
    }

    @Override
    public String toString() {
      return "Char " + value;
    }

    @Override
    public Instruction asConstInstruction(boolean hasClassInitializer, Value dest) {
      return (this == DEFAULT && hasClassInitializer) ? null : new ConstNumber(dest, value);
    }
  }

  static public class DexValueInt extends SimpleDexValue {

    public static final DexValueInt DEFAULT = new DexValueInt(0);
    public final int value;

    private DexValueInt(int value) {
      this.value = value;
    }

    public static DexValueInt create(int value) {
      return value == DEFAULT.value ? DEFAULT : new DexValueInt(value);
    }

    public int getValue() {
      return value;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      writeIntegerTo(VALUE_INT, value, Integer.BYTES, dest);
    }

    @Override
    public int hashCode() {
      return value * 11;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return other instanceof DexValueInt && value == ((DexValueInt) other).value;
    }

    @Override
    public String toString() {
      return "Int " + value;
    }

    @Override
    public Instruction asConstInstruction(boolean hasClassInitializer, Value dest) {
      return (this == DEFAULT && hasClassInitializer) ? null : new ConstNumber(dest, value);
    }
  }

  static public class DexValueLong extends SimpleDexValue {

    public static final DexValueLong DEFAULT = new DexValueLong(0);
    final long value;

    private DexValueLong(long value) {
      this.value = value;
    }

    public static DexValueLong create(long value) {
      return value == DEFAULT.value ? DEFAULT : new DexValueLong(value);
    }

    public long getValue() {
      return value;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      writeIntegerTo(VALUE_LONG, value, Long.BYTES, dest);
    }

    @Override
    public int hashCode() {
      return (int) value * 13;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return other instanceof DexValueLong && value == ((DexValueLong) other).value;
    }

    @Override
    public String toString() {
      return "Long " + value;
    }

    @Override
    public Instruction asConstInstruction(boolean hasClassInitializer, Value dest) {
      return (this == DEFAULT && hasClassInitializer) ? null : new ConstNumber(dest, value);
    }
  }

  static public class DexValueFloat extends SimpleDexValue {

    public static final DexValueFloat DEFAULT = new DexValueFloat(0);
    final float value;

    private DexValueFloat(float value) {
      this.value = value;
    }

    public static DexValueFloat create(float value) {
      return Float.compare(value, DEFAULT.value) == 0 ? DEFAULT : new DexValueFloat(value);
    }

    public float getValue() {
      return value;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      dest.forward(1);
      int length = EncodedValueUtils.putFloat(dest, value);
      dest.rewind(length + 1);
      writeHeader(VALUE_FLOAT, length - 1, dest);
      dest.forward(length);
    }

    @Override
    public int hashCode() {
      return (int) value * 19;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return (other instanceof DexValueFloat) &&
          (Float.compare(value, ((DexValueFloat) other).value) == 0);
    }

    @Override
    public String toString() {
      return "Float " + value;
    }

  }

  static public class DexValueDouble extends SimpleDexValue {

    public static final DexValueDouble DEFAULT = new DexValueDouble(0);

    final double value;

    private DexValueDouble(double value) {
      this.value = value;
    }

    public static DexValueDouble create(double value) {
      return Double.compare(value, DEFAULT.value) == 0 ? DEFAULT : new DexValueDouble(value);
    }

    public double getValue() {
      return value;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      dest.forward(1);
      int length = EncodedValueUtils.putDouble(dest, value);
      dest.rewind(length + 1);
      writeHeader(VALUE_DOUBLE, length - 1, dest);
      dest.forward(length);
    }

    @Override
    public int hashCode() {
      return (int) value * 29;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return (other instanceof DexValueDouble) &&
          (Double.compare(value, ((DexValueDouble) other).value) == 0);
    }

    @Override
    public String toString() {
      return "Double " + value;
    }
  }

  static private abstract class NestedDexValue<T extends IndexedDexItem> extends DexValue {

    public final T value;

    private NestedDexValue(T value) {
      this.value = value;
    }

    protected abstract byte getValueKind();

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      int offset = value.getOffset(mapping);
      dest.forward(1);
      int length = dest.putUnsignedEncodedValue(offset, 4);
      dest.rewind(length + 1);
      writeHeader(getValueKind(), length - 1, dest);
      dest.forward(length);
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      value.collectIndexedItems(indexedItems);
    }

    @Override
    public void sort() {
      // Intentionally empty.
    }

    @Override
    public int hashCode() {
      return value.hashCode() * 7 + getValueKind();
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (other instanceof NestedDexValue) {
        NestedDexValue<?> that = (NestedDexValue<?>) other;
        return that.getValueKind() == getValueKind() && that.value.equals(value);
      }
      return false;
    }

    @Override
    public String toString() {
      return "Item " + getValueKind() + " " + value;
    }
  }

  static public class DexValueString extends NestedDexValue<DexString> {

    public DexValueString(DexString value) {
      super(value);
    }

    public DexString getValue() {
      return value;
    }

    @Override
    protected byte getValueKind() {
      return VALUE_STRING;
    }
  }

  static public class DexValueType extends NestedDexValue<DexType> {

    public DexValueType(DexType value) {
      super(value);
    }

    @Override
    protected byte getValueKind() {
      return VALUE_TYPE;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      value.collectIndexedItems(indexedItems);
    }
  }

  static public class DexValueField extends NestedDexValue<DexField> {

    public DexValueField(DexField value) {
      super(value);
    }

    @Override
    protected byte getValueKind() {
      return VALUE_FIELD;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      value.collectIndexedItems(indexedItems);
    }
  }

  static public class DexValueMethod extends NestedDexValue<DexMethod> {

    public DexValueMethod(DexMethod value) {
      super(value);
    }

    @Override
    protected byte getValueKind() {
      return VALUE_METHOD;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      value.collectIndexedItems(indexedItems);
    }
  }

  static public class DexValueEnum extends NestedDexValue<DexField> {

    public DexValueEnum(DexField value) {
      super(value);
    }

    @Override
    protected byte getValueKind() {
      return VALUE_ENUM;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      value.collectIndexedItems(indexedItems);
    }
  }

  static public class DexValueMethodType extends NestedDexValue<DexProto> {

    public DexValueMethodType(DexProto value) {
      super(value);
    }

    @Override
    protected byte getValueKind() {
      return VALUE_METHOD_TYPE;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      value.collectIndexedItems(indexedItems);
    }
  }

  static public class DexValueArray extends DexValue {

    final DexValue[] values;

    public DexValueArray(DexValue[] values) {
      this.values = values;
    }

    public DexValue[] getValues() {
      return values;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      collectAll(indexedItems, values);
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      writeHeader(VALUE_ARRAY, 0, dest);
      dest.putUleb128(values.length);
      for (DexValue value : values) {
        value.writeTo(dest, mapping);
      }
    }

    @Override
    public void sort() {
      for (DexValue value : values) {
        value.sort();
      }
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(values);
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (other instanceof DexValueArray) {
        DexValueArray that = (DexValueArray) other;
        return Arrays.equals(that.values, values);
      }
      return false;
    }

    @Override
    public String toString() {
      return "Array " + Arrays.toString(values);
    }
  }

  static public class DexValueAnnotation extends DexValue {

    public final DexEncodedAnnotation value;

    public DexValueAnnotation(DexEncodedAnnotation value) {
      this.value = value;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      value.collectIndexedItems(indexedItems);
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      writeHeader(VALUE_ANNOTATION, 0, dest);
      FileWriter.writeEncodedAnnotation(value, dest, mapping);
    }

    @Override
    public void sort() {
      value.sort();
    }

    @Override
    public int hashCode() {
      return value.hashCode() * 7;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (other instanceof DexValueAnnotation) {
        DexValueAnnotation that = (DexValueAnnotation) other;
        return that.value.equals(value);
      }
      return false;
    }

    @Override
    public String toString() {
      return "Annotation " + value;
    }
  }

  static public class DexValueNull extends SimpleDexValue {

    public static final DexValue NULL = new DexValueNull();

    // See DexValueNull.NULL
    private DexValueNull() {
    }

    public Object getValue() {
      return null;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      writeHeader(VALUE_NULL, 0, dest);
    }

    @Override
    public int hashCode() {
      return 42;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return (other instanceof DexValueNull);
    }

    @Override
    public String toString() {
      return "Null";
    }
  }

  static public class DexValueBoolean extends SimpleDexValue {

    private static final DexValueBoolean TRUE = new DexValueBoolean(true);
    private static final DexValueBoolean FALSE = new DexValueBoolean(false);
    // Use a separate instance for the default value to distinguish it from an explicit false value.
    private static final DexValueBoolean DEFAULT = new DexValueBoolean(false);

    final boolean value;

    private DexValueBoolean(boolean value) {
      this.value = value;
    }

    public static DexValueBoolean create(boolean value) {
      return value ? TRUE : FALSE;
    }

    public boolean getValue() {
      return value;
    }

    @Override
    public void writeTo(DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
      writeHeader(VALUE_BOOLEAN, value ? 1 : 0, dest);
    }

    @Override
    public int hashCode() {
      return value ? 1234 : 4321;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      return (other instanceof DexValueBoolean) && ((DexValueBoolean) other).value == value;
    }

    @Override
    public String toString() {
      return value ? "True" : "False";
    }

    @Override
    public Instruction asConstInstruction(boolean hasClassInitializer, Value dest) {
      return (this == DEFAULT && hasClassInitializer) ? null : new ConstNumber(dest, value ? 1 : 0);
    }
  }

  static public class DexValueMethodHandle extends NestedDexValue<DexMethodHandle> {

    public DexValueMethodHandle(DexMethodHandle value) {
      super(value);
    }

    @Override
    protected byte getValueKind() {
      return VALUE_METHOD_HANDLE;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      value.collectIndexedItems(indexedItems);
    }
  }
}
