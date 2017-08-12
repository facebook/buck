// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.naming.NamingLens;

public class DexMethodHandle extends IndexedDexItem implements
    PresortedComparable<DexMethodHandle> {

  public enum MethodHandleType {
    STATIC_PUT((short) 0x00),
    STATIC_GET((short) 0x01),
    INSTANCE_PUT((short) 0x02),
    INSTANCE_GET((short) 0x03),
    INVOKE_STATIC((short) 0x04),
    INVOKE_INSTANCE((short) 0x05),
    INVOKE_CONSTRUCTOR((short) 0x06),
    INVOKE_DIRECT((short) 0x07),
    INVOKE_INTERFACE((short) 0x08),
    // Internal method handle needed by lambda desugaring.
    INVOKE_SUPER((short) 0x09);

    private final short value;

    MethodHandleType(short value) {
      this.value = value;
    }

    public short getValue() {
      return value;
    }

    public static MethodHandleType getKind(int value) {
      MethodHandleType kind;

      switch (value) {
        case 0x00:
          kind = STATIC_PUT;
          break;
        case 0x01:
          kind = STATIC_GET;
          break;
        case 0x02:
          kind = INSTANCE_PUT;
          break;
        case 0x03:
          kind = INSTANCE_GET;
          break;
        case 0x04:
          kind = INVOKE_STATIC;
          break;
        case 0x05:
          kind = INVOKE_INSTANCE;
          break;
        case 0x06:
          kind = INVOKE_CONSTRUCTOR;
          break;
        case 0x07:
          kind = INVOKE_DIRECT;
          break;
        case 0x08:
          kind = INVOKE_INTERFACE;
          break;
        case 0x09:
          kind = INVOKE_SUPER;
          break;
        default:
          throw new AssertionError();
      }

      assert kind.getValue() == value;
      return kind;
    }

    public boolean isFieldType() {
      return isStaticPut() || isStaticGet() || isInstancePut() || isInstanceGet();
    }

    public boolean isMethodType() {
      return isInvokeStatic() || isInvokeInstance() || isInvokeInterface() || isInvokeSuper()
          || isInvokeConstructor() || isInvokeDirect();
    }

    public boolean isStaticPut() {
      return this == MethodHandleType.STATIC_PUT;
    }

    public boolean isStaticGet() {
      return this == MethodHandleType.STATIC_GET;
    }

    public boolean isInstancePut() {
      return this == MethodHandleType.INSTANCE_PUT;
    }

    public boolean isInstanceGet() {
      return this == MethodHandleType.INSTANCE_GET;
    }

    public boolean isInvokeStatic() {
      return this == MethodHandleType.INVOKE_STATIC;
    }

    public boolean isInvokeDirect() {
      return this == MethodHandleType.INVOKE_DIRECT;
    }

    public boolean isInvokeInstance() {
      return this == MethodHandleType.INVOKE_INSTANCE;
    }

    public boolean isInvokeInterface() {
      return this == MethodHandleType.INVOKE_INTERFACE;
    }

    public boolean isInvokeSuper() {
      return this == MethodHandleType.INVOKE_SUPER;
    }

    public boolean isInvokeConstructor() {
      return this == MethodHandleType.INVOKE_CONSTRUCTOR;
    }
  }

  public MethodHandleType type;
  public Descriptor<? extends DexItem, ? extends Descriptor<?,?>> fieldOrMethod;

  public DexMethodHandle(
      MethodHandleType type,
      Descriptor<? extends DexItem, ? extends Descriptor<?,?>> fieldOrMethod) {
    this.type = type;
    this.fieldOrMethod = fieldOrMethod;
  }

  @Override
  public int computeHashCode() {
    return type.hashCode() + fieldOrMethod.computeHashCode() * 7;
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexMethodHandle) {
      DexMethodHandle o = (DexMethodHandle) other;
      return type.equals(o.type) && fieldOrMethod.equals(o.fieldOrMethod);
    }
    return false;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("MethodHandle: {")
            .append(type)
            .append(", ")
            .append(fieldOrMethod.toSourceString())
            .append("}");
    return builder.toString();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    if (indexedItems.addMethodHandle(this)) {
      fieldOrMethod.collectIndexedItems(indexedItems);
    }
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  // TODO(mikaelpeltier): Adapt syntax when invoke-custom will be available into smali.
  @Override
  public String toSmaliString() {
    return toString();
  }

  public boolean isFieldHandle() {
    return type.isFieldType();
  }

  public boolean isMethodHandle() {
    return type.isMethodType();
  }

  public boolean isStaticHandle() {
    return type.isStaticPut() || type.isStaticGet() || type.isInvokeStatic();
  }

  public DexMethod asMethod() {
    assert isMethodHandle();
    return (DexMethod) fieldOrMethod;
  }

  public DexField asField() {
    assert isFieldHandle();
    return (DexField) fieldOrMethod;
  }

  @Override
  public int slowCompareTo(DexMethodHandle other) {
    int result = type.getValue() - other.type.getValue();
    if (result == 0) {
      if (isFieldHandle()) {
        result = asField().slowCompareTo(other.asField());
      } else {
        assert isMethodHandle();
        result = asMethod().slowCompareTo(other.asMethod());
      }
    }
    return result;
  }

  @Override
  public int slowCompareTo(DexMethodHandle other, NamingLens namingLens) {
    int result = type.getValue() - other.type.getValue();
    if (result == 0) {
      if (isFieldHandle()) {
        result = asField().slowCompareTo(other.asField(), namingLens);
      } else {
        assert isMethodHandle();
        result = asMethod().slowCompareTo(other.asMethod(), namingLens);
      }
    }
    return result;
  }

  @Override
  public int layeredCompareTo(DexMethodHandle other, NamingLens namingLens) {
    int result = type.getValue() - other.type.getValue();
    if (result == 0) {
      if (isFieldHandle()) {
        result = asField().layeredCompareTo(other.asField(), namingLens);
      } else {
        assert isMethodHandle();
        result = asMethod().layeredCompareTo(other.asMethod(), namingLens);
      }
    }
    return result;
  }

  @Override
  public int compareTo(DexMethodHandle other) {
    return sortedCompareTo(other.getSortedIndex());
  }
}
