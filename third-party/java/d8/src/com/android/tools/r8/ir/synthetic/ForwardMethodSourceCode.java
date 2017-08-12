// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.synthetic;

import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.List;

// Source code representing simple forwarding method.
public final class ForwardMethodSourceCode extends SingleBlockSourceCode {

  private final DexType targetReceiver;
  private final DexMethod target;
  private final Invoke.Type invokeType;

  public ForwardMethodSourceCode(DexType receiver, DexProto proto,
      DexType targetReceiver, DexMethod target, Invoke.Type invokeType) {
    super(receiver, proto);
    assert (targetReceiver == null) == (invokeType == Invoke.Type.STATIC);

    this.target = target;
    this.targetReceiver = targetReceiver;
    this.invokeType = invokeType;
    assert checkSignatures();

    switch (invokeType) {
      case STATIC:
      case SUPER:
      case INTERFACE:
      case VIRTUAL:
        break;
      default:
        throw new Unimplemented("Invoke type " + invokeType + " is not yet supported.");
    }
  }

  private boolean checkSignatures() {
    List<DexType> sourceParams = new ArrayList<>();
    if (receiver != null) {
      sourceParams.add(receiver);
    }
    sourceParams.addAll(Lists.newArrayList(proto.parameters.values));

    List<DexType> targetParams = new ArrayList<>();
    if (targetReceiver != null) {
      targetParams.add(targetReceiver);
    }
    targetParams.addAll(Lists.newArrayList(target.proto.parameters.values));

    assert sourceParams.size() == targetParams.size();
    for (int i = 0; i < sourceParams.size(); i++) {
      DexType source = sourceParams.get(i);
      DexType target = targetParams.get(i);

      // We assume source is compatible with target if they both are classes.
      // This check takes care of receiver widening conversion but does not
      // many others, like conversion from an array to Object.
      assert (source.isClassType() && target.isClassType()) || source == target;
    }

    assert this.proto.returnType == target.proto.returnType;
    return true;
  }

  @Override
  protected void prepareInstructions() {
    // Prepare call arguments.
    List<ValueType> argValueTypes = new ArrayList<>();
    List<Integer> argRegisters = new ArrayList<>();

    if (receiver != null) {
      argValueTypes.add(ValueType.OBJECT);
      argRegisters.add(getReceiverRegister());
    }

    DexType[] accessorParams = proto.parameters.values;
    for (int i = 0; i < accessorParams.length; i++) {
      argValueTypes.add(ValueType.fromDexType(accessorParams[i]));
      argRegisters.add(getParamRegister(i));
    }

    // Method call to the target method.
    add(builder -> builder.addInvoke(this.invokeType,
        this.target, this.target.proto, argValueTypes, argRegisters));

    // Does the method return value?
    if (proto.returnType.isVoidType()) {
      add(IRBuilder::addReturn);
    } else {
      ValueType valueType = ValueType.fromDexType(proto.returnType);
      int tempValue = nextRegister(valueType);
      add(builder -> builder.addMoveResult(tempValue));
      add(builder -> builder.addReturn(valueType, tempValue));
    }
  }
}
