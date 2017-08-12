// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.IRBuilder;
import java.util.ArrayList;
import java.util.List;

// Source code representing synthesized accessor method.
final class AccessorMethodSourceCode extends SynthesizedLambdaSourceCode {

  AccessorMethodSourceCode(LambdaClass lambda) {
    super(lambda, lambda.target.callTarget, null /* no receiver for static method */);
    // We should never need an accessor for interface methods since
    // they are supposed to be public.
    assert !descriptor().implHandle.type.isInvokeInterface();
    assert checkSignatures();
  }

  private boolean checkSignatures() {
    DexMethodHandle implHandle = descriptor().implHandle;
    assert implHandle != null;

    DexType[] accessorParams = proto.parameters.values;
    DexMethod implMethod = implHandle.asMethod();
    DexProto implProto = implMethod.proto;
    DexType[] implParams = implProto.parameters.values;

    int index = 0;
    if (implHandle.type.isInvokeInstance() || implHandle.type.isInvokeDirect()) {
      assert accessorParams[index] == descriptor().getImplReceiverType();
      index++;
    }

    for (DexType implParam : implParams) {
      assert accessorParams[index] == implParam;
      index++;
    }
    assert index == accessorParams.length;

    assert delegatingToConstructor()
        ? this.proto.returnType == implMethod.holder
        : this.proto.returnType == implProto.returnType;
    return true;
  }

  private boolean isPrivateMethod() {
    // We should be able to find targets for all private impl-methods, so
    // we can rely on knowing accessibility flags for them.
    MethodAccessFlags flags = descriptor().getAccessibility();
    return flags != null && flags.isPrivate();
  }

  // Are we delegating to a constructor?
  private boolean delegatingToConstructor() {
    return descriptor().implHandle.type.isInvokeConstructor();
  }

  private Invoke.Type inferInvokeType() {
    switch (descriptor().implHandle.type) {
      case INVOKE_INSTANCE:
        return Invoke.Type.VIRTUAL;
      case INVOKE_STATIC:
        return Invoke.Type.STATIC;
      case INVOKE_DIRECT:
      case INVOKE_CONSTRUCTOR:
        return Invoke.Type.DIRECT;
      case INVOKE_INTERFACE:
        throw new Unreachable("Accessor for an interface method?");
      default:
        throw new Unreachable();
    }
  }

  @Override
  protected void prepareInstructions() {
    DexMethod implMethod = descriptor().implHandle.asMethod();
    DexType[] accessorParams = proto.parameters.values;

    // Prepare call arguments.
    List<ValueType> argValueTypes = new ArrayList<>();
    List<Integer> argRegisters = new ArrayList<>();

    // If we are delegating to a constructor, we need to create the instance
    // first. This instance will be the first argument to the call.
    if (delegatingToConstructor()) {
      int instance = nextRegister(ValueType.OBJECT);
      add(builder -> builder.addNewInstance(instance, implMethod.holder));
      argValueTypes.add(ValueType.OBJECT);
      argRegisters.add(instance);
    }

    for (int i = 0; i < accessorParams.length; i++) {
      DexType param = accessorParams[i];
      argValueTypes.add(ValueType.fromDexType(param));
      argRegisters.add(getParamRegister(i));
    }

    // Method call to the original impl-method.
    add(builder -> builder.addInvoke(inferInvokeType(),
        implMethod, implMethod.proto, argValueTypes, argRegisters));

    // Does the method have return value?
    if (proto.returnType == factory().voidType) {
      add(IRBuilder::addReturn);
    } else if (delegatingToConstructor()) {
      // Return newly created instance
      add(builder -> builder.addReturn(ValueType.OBJECT, argRegisters.get(0)));
    } else {
      ValueType valueType = ValueType.fromDexType(proto.returnType);
      int tempValue = nextRegister(valueType);
      add(builder -> builder.addMoveResult(tempValue));
      add(builder -> builder.addReturn(valueType, tempValue));
    }
  }
}
