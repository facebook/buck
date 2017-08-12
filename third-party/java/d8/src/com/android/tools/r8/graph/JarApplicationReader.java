// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.errors.InternalCompilerError;
import com.android.tools.r8.graph.DexMethodHandle.MethodHandleType;
import com.android.tools.r8.utils.InternalOptions;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Type;

/**
 * Common structures used while reading in a Java application from jar files.
 *
 * The primary use of this class is to canonicalize dex items during read.
 * The addition of classes to the builder also takes place through this class.
 * It does not currently support multithreaded reading.
 */
public class JarApplicationReader {

  public final InternalOptions options;

  ConcurrentHashMap<String, DexString> stringCache = new ConcurrentHashMap<>();

  public JarApplicationReader(InternalOptions options) {
    this.options = options;
  }

  public DexItemFactory getFactory() {
    return options.itemFactory;
  }

  public DexString getString(String string) {
    return stringCache.computeIfAbsent(string, options.itemFactory::createString);
  }

  public DexType getType(Type type) {
    return getTypeFromDescriptor(type.getDescriptor());
  }

  public DexType getTypeFromName(String name) {
    assert isValidInternalName(name);
    return getType(Type.getObjectType(name));
  }

  public DexType getTypeFromDescriptor(String desc) {
    assert isValidDescriptor(desc);
    return options.itemFactory.createType(getString(desc));
  }

  public DexTypeList getTypeListFromNames(String[] names) {
    if (names.length == 0) {
      return DexTypeList.empty();
    }
    DexType[] types = new DexType[names.length];
    for (int i = 0; i < names.length; i++) {
      types[i] = getTypeFromName(names[i]);
    }
    return new DexTypeList(types);
  }

  public DexTypeList getTypeListFromDescriptors(String[] descriptors) {
    if (descriptors.length == 0) {
      return DexTypeList.empty();
    }
    DexType[] types = new DexType[descriptors.length];
    for (int i = 0; i < descriptors.length; i++) {
      types[i] = getTypeFromDescriptor(descriptors[i]);
    }
    return new DexTypeList(types);
  }

  public DexField getField(String owner, String name, String desc) {
    return getField(getTypeFromName(owner), name, desc);
  }

  public DexField getField(DexType owner, String name, String desc) {
    return options.itemFactory.createField(owner, getTypeFromDescriptor(desc), getString(name));
  }

  public DexMethod getMethod(String owner, String name, String desc) {
    return getMethod(getTypeFromName(owner), name, desc);
  }

  public DexMethod getMethod(DexType owner, String name, String desc) {
    return options.itemFactory.createMethod(owner, getProto(desc), getString(name));
  }

  public DexCallSite getCallSite(String methodName, String methodProto,
      DexMethodHandle bootstrapMethod, List<DexValue> bootstrapArgs) {
    return options.itemFactory.createCallSite(
        getString(methodName), getProto(methodProto), bootstrapMethod, bootstrapArgs);
  }

  public DexMethodHandle getMethodHandle(
      MethodHandleType type,
      Descriptor<? extends DexItem, ? extends Descriptor<?, ?>> fieldOrMethod) {
    return options.itemFactory.createMethodHandle(type, fieldOrMethod);
  }

  public DexProto getProto(String desc) {
    assert isValidDescriptor(desc);
    Type returnType = Type.getReturnType(desc);
    Type[] arguments = Type.getArgumentTypes(desc);

    StringBuilder shortyDescriptor = new StringBuilder();
    String[] argumentDescriptors = new String[arguments.length];
    shortyDescriptor.append(getShortyDescriptor(returnType));
    for (int i = 0; i < arguments.length; i++) {
      shortyDescriptor.append(getShortyDescriptor(arguments[i]));
      argumentDescriptors[i] = arguments[i].getDescriptor();
    }
    DexProto proto = options.itemFactory.createProto(
        getTypeFromDescriptor(returnType.getDescriptor()),
        getString(shortyDescriptor.toString()),
        getTypeListFromDescriptors(argumentDescriptors));
    return proto;
  }

  private static String getShortyDescriptor(Type type) {
    switch (type.getSort()) {
      case Type.METHOD:
        throw new InternalCompilerError("Cannot produce a shorty decriptor for methods");
      case Type.ARRAY:
      case Type.OBJECT:
        return "L";
      default:
        return type.getDescriptor();
    }
  }

  private boolean isValidDescriptor(String desc) {
    return Type.getType(desc).getDescriptor().equals(desc);
  }

  private boolean isValidInternalName(String name) {
    return Type.getObjectType(name).getInternalName().equals(name);
  }
}
