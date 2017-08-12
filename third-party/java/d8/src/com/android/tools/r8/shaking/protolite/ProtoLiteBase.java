// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking.protolite;

import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;

/**
 * Contains common definitions used by the {@link ProtoLiteExtension} for tree shaking and the
 * corresponding {@link ProtoLitePruner} code rewriting.
 */
abstract class ProtoLiteBase {

  static final int GETTER_NAME_PREFIX_LENGTH = 3;
  static final int COUNT_POSTFIX_LENGTH = 5;

  final AppInfoWithSubtyping appInfo;

  final DexType messageType;
  final DexString dynamicMethodName;
  final DexString writeToMethodName;
  final DexString getSerializedSizeMethodName;
  final DexString constructorMethodName;
  final DexString setterNamePrefix;
  final DexString getterNamePrefix;
  final DexString bitFieldPrefix;
  final DexString underscore;

  ProtoLiteBase(AppInfoWithSubtyping appInfo) {
    this.appInfo = appInfo;
    DexItemFactory factory = appInfo.dexItemFactory;
    this.messageType = factory.createType("Lcom/google/protobuf/GeneratedMessageLite;");
    this.dynamicMethodName = factory.createString("dynamicMethod");
    this.writeToMethodName = factory.createString("writeTo");
    this.getSerializedSizeMethodName = factory.createString("getSerializedSize");
    this.constructorMethodName = factory.constructorMethodName;
    this.setterNamePrefix = factory.createString("set");
    this.getterNamePrefix = factory.createString("get");
    this.bitFieldPrefix = factory.createString("bitField");
    this.underscore = factory.createString("_");
    assert getterNamePrefix.size == GETTER_NAME_PREFIX_LENGTH;
  }

  /**
   * Returns true of the given method is a setter on a message class that does need processing
   * by this phase of proto lite shaking.
   * <p>
   * False positives are ok.
   */
  abstract boolean isSetterThatNeedsProcessing(DexEncodedMethod method);

  DexField getterToField(DexMethod getter) {
    return getterToField(getter, 0);
  }

  DexField getterToField(DexMethod getter, int postfixLength) {
    String getterName = getter.name.toString();
    assert getterName.length() > GETTER_NAME_PREFIX_LENGTH + postfixLength;
    String fieldName = Character.toLowerCase(getterName.charAt(GETTER_NAME_PREFIX_LENGTH))
        + getterName.substring(GETTER_NAME_PREFIX_LENGTH + 1, getterName.length() - postfixLength)
        + "_";
    DexItemFactory factory = appInfo.dexItemFactory;
    return factory
        .createField(getter.holder, getter.proto.returnType, factory.createString(fieldName));
  }

  boolean hasSingleIntArgument(DexMethod method) {
    return method.getArity() == 1
        && method.proto.parameters.values[0] == appInfo.dexItemFactory.intType;
  }

  public boolean appliesTo(DexEncodedMethod method) {
    if (!method.method.holder.isSubtypeOf(messageType, appInfo)) {
      return false;
    }
    DexClass clazz = appInfo.definitionFor(method.method.holder);
    // We only care for the actual leaf classes that implement a specific proto.
    if (!clazz.accessFlags.isFinal()) {
      return false;
    }
    DexString methodName = method.method.name;
    // We could be even more precise here and check for the signature. However, there is only
    // one of each method generated.
    return methodName == dynamicMethodName
        || methodName == writeToMethodName
        || methodName == getSerializedSizeMethodName
        || methodName == constructorMethodName
        || isSetterThatNeedsProcessing(method);
  }

}
