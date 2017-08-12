// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.android.tools.r8.graph.DexValue.DexValueMethodType;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.google.common.io.BaseEncoding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class DexCallSite extends IndexedDexItem {

  public final DexString methodName;
  public final DexProto methodProto;

  public final DexMethodHandle bootstrapMethod;
  public final List<DexValue> bootstrapArgs;

  private DexEncodedArray encodedArray = null;

  DexCallSite(DexString methodName, DexProto methodProto,
      DexMethodHandle bootstrapMethod, List<DexValue> bootstrapArgs) {
    assert methodName != null;
    assert methodProto != null;
    assert bootstrapMethod != null;
    assert bootstrapArgs != null;

    this.methodName = methodName;
    this.methodProto = methodProto;
    this.bootstrapMethod = bootstrapMethod;
    this.bootstrapArgs = bootstrapArgs;
  }

  @Override
  public int computeHashCode() {
    return methodName.hashCode()
        + methodProto.hashCode() * 7
        + bootstrapMethod.hashCode() * 31
        + bootstrapArgs.hashCode() * 101;
  }

  @Override
  public boolean computeEquals(Object other) {
    // Call sites are equal only when this == other, which was already computed by the caller of
    // computeEquals. Do not share call site entries, each invoke-custom must have its own
    // call site, but the content of the entry (encoded array) in the data section can be shared.
    return false;
  }

  @Override
  public String toString() {
    StringBuilder builder =
        new StringBuilder("CallSite: { Name: ").append(methodName.toSourceString())
            .append(", Proto: ").append(methodProto.toSourceString())
            .append(", ").append(bootstrapMethod.toSourceString());
    String sep = ", Args: ";
    for (DexItem arg : bootstrapArgs) {
      builder.append(sep).append(arg.toSourceString());
      sep = ", ";
    }
    builder.append('}');
    return builder.toString();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    if (indexedItems.addCallSite(this)) {
      methodName.collectIndexedItems(indexedItems);
      methodProto.collectIndexedItems(indexedItems);
      bootstrapMethod.collectIndexedItems(indexedItems);
      for (DexValue arg : bootstrapArgs) {
        arg.collectIndexedItems(indexedItems);
      }
    }
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    mixedItems.add(getEncodedArray());
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

  public String getHash() {
    return new HashBuilder().build();
  }

  private final class HashBuilder {
    private ByteArrayOutputStream bytes;
    private ObjectOutputStream out;

    private void write(DexString string) throws IOException {
      out.writeInt(string.size); // To avoid same-prefix problem
      out.write(string.content);
    }

    private void write(DexType type) throws IOException {
      write(type.descriptor);
    }

    private void write(DexMethodHandle methodHandle) throws IOException {
      out.writeShort(methodHandle.type.getValue());
      if (methodHandle.isFieldHandle()) {
        write(methodHandle.asField());
      } else {
        write(methodHandle.asMethod());
      }
    }

    private void write(DexProto proto) throws IOException {
      write(proto.shorty);
      write(proto.returnType);
      DexType[] params = proto.parameters.values;
      out.writeInt(params.length);
      for (DexType param : params) {
        write(param);
      }
    }

    private void write(DexMethod method) throws IOException {
      write(method.holder);
      write(method.proto);
      write(method.name);
    }

    private void write(DexField field) throws IOException {
      write(field.clazz);
      write(field.type);
      write(field.name);
    }

    private void write(List<DexValue> args) throws IOException {
      out.writeInt(args.size());
      for (DexValue arg : args) {
        // String, Class, Integer, Long, Float, Double, MethodHandle, MethodType
        if (arg instanceof DexValue.DexValueString) {
          out.writeByte(0);
          write(((DexValue.DexValueString) arg).value);
          continue;
        }

        if (arg instanceof DexValue.DexValueType) {
          out.writeByte(1);
          write(((DexValue.DexValueType) arg).value);
          continue;
        }

        if (arg instanceof DexValue.DexValueInt) {
          out.writeByte(2);
          out.writeInt(((DexValue.DexValueInt) arg).value);
          continue;
        }

        if (arg instanceof DexValue.DexValueLong) {
          out.writeByte(3);
          out.writeLong(((DexValue.DexValueLong) arg).value);
          continue;
        }

        if (arg instanceof DexValue.DexValueFloat) {
          out.writeByte(4);
          out.writeFloat(((DexValue.DexValueFloat) arg).value);
          continue;
        }

        if (arg instanceof DexValue.DexValueDouble) {
          out.writeByte(5);
          out.writeDouble(((DexValue.DexValueDouble) arg).value);
          continue;
        }

        if (arg instanceof DexValue.DexValueMethodHandle) {
          out.writeByte(6);
          write(((DexValue.DexValueMethodHandle) arg).value);
          continue;
        }

        assert arg instanceof DexValue.DexValueMethodType;
        out.writeByte(7);
        write(((DexValue.DexValueMethodType) arg).value);
      }
    }

    String build() {
      try {
        bytes = new ByteArrayOutputStream();
        out = new ObjectOutputStream(bytes);

        // We will generate SHA-1 hash of the call site information based on call site
        // attributes used in equality comparison, such that if the two call sites are
        // different their hashes should also be different.
        write(methodName);
        write(methodProto);
        write(bootstrapMethod);
        write(bootstrapArgs);
        out.close();

        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(bytes.toByteArray());
        return BaseEncoding.base64Url().omitPadding().encode(digest.digest());
      } catch (NoSuchAlgorithmException | IOException ex) {
        throw new Unreachable("Cannot get SHA-1 message digest");
      }
    }
  }

  public DexEncodedArray getEncodedArray() {
    if (encodedArray == null) {
      // 3 is the fixed size of the call site
      DexValue[] callSitesValues = new DexValue[3 + bootstrapArgs.size()];
      int valuesIndex = 0;
      callSitesValues[valuesIndex++] = new DexValueMethodHandle(bootstrapMethod);
      callSitesValues[valuesIndex++] = new DexValueString(methodName);
      callSitesValues[valuesIndex++] = new DexValueMethodType(methodProto);
      for (DexValue extraArgValue : bootstrapArgs) {
        callSitesValues[valuesIndex++] = extraArgValue;
      }
      encodedArray = new DexEncodedArray(callSitesValues);
    }

    return encodedArray;
  }
}
