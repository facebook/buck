// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Source code representing synthesized lambda main method
final class LambdaMainMethodSourceCode extends SynthesizedLambdaSourceCode {

  LambdaMainMethodSourceCode(LambdaClass lambda, DexMethod mainMethod) {
    super(lambda, mainMethod);
  }

  private boolean checkSignatures(
      DexType[] captures, DexType[] enforcedParams, DexType enforcedReturnType,
      List<DexType> implReceiverAndArgs, DexType implReturnType) {
    List<DexType> capturesAndParams = new ArrayList<>();
    capturesAndParams.addAll(Lists.newArrayList(captures));
    capturesAndParams.addAll(Lists.newArrayList(enforcedParams));

    int size = capturesAndParams.size();
    if (size != implReceiverAndArgs.size()) {
      return false;
    }

    for (int i = 0; i < size; i++) {
      if (!isSameOrAdaptableTo(capturesAndParams.get(i), implReceiverAndArgs.get(i))) {
        return false;
      }
    }

    if (!enforcedReturnType.isVoidType()
        && !isSameOrAdaptableTo(implReturnType, enforcedReturnType)) {
      return false;
    }
    return true;
  }

  private DexType getPrimitiveFromBoxed(DexType boxedPrimitive) {
    DexString descriptor = boxedPrimitive.descriptor;
    DexItemFactory factory = factory();
    if (descriptor == factory.boxedBooleanDescriptor) {
      return factory.booleanType;
    }
    if (descriptor == factory.boxedByteDescriptor) {
      return factory.byteType;
    }
    if (descriptor == factory.boxedCharDescriptor) {
      return factory.charType;
    }
    if (descriptor == factory.boxedShortDescriptor) {
      return factory.shortType;
    }
    if (descriptor == factory.boxedIntDescriptor) {
      return factory.intType;
    }
    if (descriptor == factory.boxedLongDescriptor) {
      return factory.longType;
    }
    if (descriptor == factory.boxedFloatDescriptor) {
      return factory.floatType;
    }
    if (descriptor == factory.boxedDoubleDescriptor) {
      return factory.doubleType;
    }
    return null;
  }

  private DexType getBoxedForPrimitiveType(DexType primitive) {
    switch (primitive.descriptor.content[0]) {
      case 'Z':  // byte
        return factory().boxedBooleanType;
      case 'B':  // byte
        return factory().boxedByteType;
      case 'S':  // short
        return factory().boxedShortType;
      case 'C':  // char
        return factory().boxedCharType;
      case 'I':  // int
        return factory().boxedIntType;
      case 'J':  // long
        return factory().boxedLongType;
      case 'F':  // float
        return factory().boxedFloatType;
      case 'D':  // double
        return factory().boxedDoubleType;
      default:
        throw new Unreachable("Invalid primitive type descriptor: " + primitive);
    }
  }

  // Checks if the types are the same OR type `a` is adaptable to type `b`.
  private boolean isSameOrAdaptableTo(DexType a, DexType b) {
    if (a == b) {
      return true;
    }

    DexItemFactory factory = factory();
    if (a.isArrayType()) {
      // Arrays are only adaptable to java.lang.Object.
      return b == factory.objectType;
    }

    if (a.isPrimitiveType()) {
      if (b.isPrimitiveType()) {
        return isSameOrAdaptableTo(a.descriptor.content[0], b.descriptor.content[0]);
      }

      // `a` is primitive and `b` is a supertype of the boxed type `a`.
      DexType boxedPrimitiveType = getBoxedForPrimitiveType(a);
      if (b == boxedPrimitiveType || b == factory.objectType) {
        return true;
      }
      return boxedPrimitiveType != factory.boxedCharType
          && boxedPrimitiveType != factory.boxedBooleanType
          && b.descriptor == factory.boxedNumberDescriptor;
    }

    if (b.isPrimitiveType()) {
      if (a == factory.objectType) {
        // `a` is java.lang.Object in which case we assume it represented by
        // proper boxed type.
        return true;
      }
      // `a` is a boxed type for `a*` which can be
      // widened to primitive type `b`.
      DexType unboxedA = getPrimitiveFromBoxed(a);
      return unboxedA != null &&
          isSameOrAdaptableTo(unboxedA.descriptor.content[0], b.descriptor.content[0]);
    }

    // Otherwise `a` should be a reference type derived from `b`.
    // NOTE: we don't check `b` for being actually a supertype, since we
    // might not have full classpath with inheritance information to do that.
    // We assume this requirement stands and will be caught by cast
    // instruction anyways in most cases.
    return a.isClassType() && b.isClassType();
  }

  // For two primitive types `a` is adjustable to `b` iff `a` is the same as `b`
  // or can be converted to `b` via a primitive widening conversion.
  private boolean isSameOrAdaptableTo(byte from, byte to) {
    if (from == to) {
      return true;
    }
    switch (from) {
      case 'B':  // byte
        return to == 'S' || to == 'I' || to == 'J' || to == 'F' || to == 'D';
      case 'S':  // short
      case 'C':  // char
        return to == 'I' || to == 'J' || to == 'F' || to == 'D';
      case 'I':  // int
        return to == 'J' || to == 'F' || to == 'D';
      case 'J':  // long
        return to == 'F' || to == 'D';
      case 'F':  // float
        return to == 'D';
      case 'Z':  // boolean
      case 'D':  // double
        return false;
      default:
        throw new Unreachable("Invalid primitive type descriptor: " + from);
    }
  }

  @Override
  protected void prepareInstructions() {
    DexType[] capturedTypes = captures();
    DexType[] erasedParams = descriptor().erasedProto.parameters.values;
    DexType erasedReturnType = descriptor().erasedProto.returnType;
    DexType[] enforcedParams = descriptor().enforcedProto.parameters.values;
    DexType enforcedReturnType = descriptor().enforcedProto.returnType;

    LambdaClass.Target target = lambda.target;
    DexMethod methodToCall = target.callTarget;

    // Only constructor call should use direct invoke type since super
    // and private methods require accessor methods.
    boolean constructorTarget = target.invokeType == Invoke.Type.DIRECT;
    assert !constructorTarget || methodToCall.name == factory().constructorMethodName;

    List<DexType> implReceiverAndArgs = new ArrayList<>();
    if (target.invokeType == Invoke.Type.VIRTUAL || target.invokeType == Invoke.Type.INTERFACE) {
      implReceiverAndArgs.add(methodToCall.holder);
    }
    implReceiverAndArgs.addAll(Lists.newArrayList(methodToCall.proto.parameters.values));
    DexType implReturnType = methodToCall.proto.returnType;

    assert target.invokeType == Invoke.Type.STATIC
        || target.invokeType == Invoke.Type.VIRTUAL
        || target.invokeType == Invoke.Type.DIRECT
        || target.invokeType == Invoke.Type.INTERFACE;
    assert checkSignatures(capturedTypes, enforcedParams,
        enforcedReturnType, implReceiverAndArgs,
        constructorTarget ? target.callTarget.holder : implReturnType);

    // Prepare call arguments.
    List<ValueType> argValueTypes = new ArrayList<>();
    List<Integer> argRegisters = new ArrayList<>();

    // If the target is a constructor, we need to create the instance first.
    // This instance will be the first argument to the call.
    if (constructorTarget) {
      int instance = nextRegister(ValueType.OBJECT);
      add(builder -> builder.addNewInstance(instance, methodToCall.holder));
      argValueTypes.add(ValueType.OBJECT);
      argRegisters.add(instance);
    }

    // Load captures if needed.
    int capturedValues = capturedTypes.length;
    for (int i = 0; i < capturedValues; i++) {
      ValueType valueType = ValueType.fromDexType(capturedTypes[i]);
      int register = nextRegister(valueType);

      argValueTypes.add(valueType);
      argRegisters.add(register);

      // Read field into tmp local.
      DexField field = lambda.getCaptureField(i);
      add(builder -> builder.addInstanceGet(register, getReceiverRegister(), field));
    }

    // Prepare arguments.
    for (int i = 0; i < erasedParams.length; i++) {
      DexType expectedParamType = implReceiverAndArgs.get(i + capturedValues);
      argValueTypes.add(ValueType.fromDexType(expectedParamType));
      argRegisters.add(prepareParameterValue(
          getParamRegister(i), erasedParams[i], enforcedParams[i], expectedParamType));
    }

    // Method call to the method implementing lambda or method-ref.
    add(builder -> builder.addInvoke(target.invokeType,
        methodToCall, methodToCall.proto, argValueTypes, argRegisters));

    // Does the method have return value?
    if (enforcedReturnType.isVoidType()) {
      add(IRBuilder::addReturn);
    } else if (constructorTarget) {
      // Return newly created instance
      int instanceRegister = argRegisters.get(0);
      int adjustedValue = prepareReturnValue(instanceRegister,
          erasedReturnType, enforcedReturnType, methodToCall.holder);
      add(builder -> builder.addReturn(
          ValueType.fromDexType(erasedReturnType), adjustedValue));
    } else {
      ValueType implValueType = ValueType.fromDexType(implReturnType);
      int tempValue = nextRegister(implValueType);
      add(builder -> builder.addMoveResult(tempValue));
      int adjustedValue = prepareReturnValue(tempValue,
          erasedReturnType, enforcedReturnType, methodToCall.proto.returnType);
      ValueType adjustedValueType = ValueType.fromDexType(erasedReturnType);
      add(builder -> builder.addReturn(adjustedValueType, adjustedValue));
    }
  }

  // Adds necessary casts and transformations to adjust the value
  // returned by impl-method to expected return type of the method.
  private int prepareReturnValue(int register,
      DexType erasedType, DexType enforcedType, DexType actualType) {
    // `actualType` must be adjusted to `enforcedType` first.
    register = adjustType(register, actualType, enforcedType);

    // `erasedType` and `enforcedType` may only differ when they both
    // are class types and `erasedType` is a base type of `enforcedType`,
    // so no transformation is actually needed.
    assert LambdaDescriptor.isSameOrDerived(factory(), enforcedType, erasedType);
    return register;
  }

  // Adds necessary casts and transformations to adjust parameter
  // value to the expected type of method-impl argument.
  //
  // Note that the original parameter type (`erasedType`) may need to
  // be converted to enforced parameter type (`enforcedType`), which,
  // in its turn, may need to be adjusted to the parameter type of
  // the impl-method (`expectedType`).
  private int prepareParameterValue(int register,
      DexType erasedType, DexType enforcedType, DexType expectedType) {
    register = enforceParameterType(register, erasedType, enforcedType);
    register = adjustType(register, enforcedType, expectedType);
    return register;
  }

  private int adjustType(int register, DexType fromType, DexType toType) {
    if (fromType == toType) {
      return register;
    }

    boolean fromTypePrimitive = fromType.isPrimitiveType();
    boolean toTypePrimitive = toType.isPrimitiveType();

    // If both are primitive they must be convertible via primitive widening conversion.
    if (fromTypePrimitive && toTypePrimitive) {
      return addPrimitiveWideningConversion(register, fromType, toType);
    }

    // If the first one is a boxed primitive type and the second one is a primitive
    // type, the value must be unboxed and converted to the resulting type via primitive
    // widening conversion.
    if (toTypePrimitive) {
      DexType boxedType = fromType;
      if (boxedType == factory().objectType) {
        // We are in situation when from(=java.lang.Object) is being adjusted to a
        // primitive type, in which case we assume it is of proper box type.
        boxedType = getBoxedForPrimitiveType(toType);
        register = castToBoxedType(register, boxedType);
      }
      DexType fromTypeAsPrimitive = getPrimitiveFromBoxed(boxedType);
      if (fromTypeAsPrimitive != null) {
        int unboxedRegister = addPrimitiveUnboxing(register, fromTypeAsPrimitive, boxedType);
        return addPrimitiveWideningConversion(unboxedRegister, fromTypeAsPrimitive, toType);
      }
    }

    // If the first one is a primitive type and the second one is a boxed
    // type for this primitive type, just box the value.
    if (fromTypePrimitive) {
      DexType boxedFromType = getBoxedForPrimitiveType(fromType);
      if (toType == boxedFromType ||
          toType == factory().objectType ||
          (boxedFromType != factory().booleanType &&
              boxedFromType != factory().charType &&
              toType == factory().boxedNumberType)) {
        return addPrimitiveBoxing(register, fromType, boxedFromType);
      }
    }

    if (fromType.isArrayType() && toType == factory().objectType) {
      // If `fromType` is an array and `toType` is java.lang.Object, no cast is needed.
      return register;
    }

    if (fromType.isClassType() && toType.isClassType()) {
      // If `fromType` and `toType` are both reference types, `fromType` must
      // be deriving from `toType`.
      // NOTE: we don't check `toType` for being actually a supertype, since we
      // might not have full classpath with inheritance information to do that.
      return register;
    }

    throw new Unreachable("Unexpected type adjustment from "
        + fromType.toSourceString() + " to " + toType);
  }

  private int addPrimitiveWideningConversion(int register, DexType fromType, DexType toType) {
    assert fromType.isPrimitiveType() && toType.isPrimitiveType();
    if (fromType == toType) {
      return register;
    }

    NumericType from = NumericType.fromDexType(fromType);
    NumericType to = NumericType.fromDexType(toType);

    if (from != null && to != null) {
      assert from != to;

      switch (to) {
        case SHORT: {
          if (from != NumericType.BYTE) {
            break; // Only BYTE can be converted to SHORT via widening conversion.
          }
          int result = nextRegister(ValueType.INT);
          add(builder -> builder.addConversion(to, NumericType.INT, result, register));
          return result;
        }

        case INT:
          if (from == NumericType.BYTE || from == NumericType.CHAR || from == NumericType.SHORT) {
            return register; // No actual conversion is needed.
          }
          break;

        case LONG: {
          if (from == NumericType.FLOAT || from == NumericType.DOUBLE) {
            break; // Not a widening conversion.
          }
          int result = nextRegister(ValueType.LONG);
          add(builder -> builder.addConversion(to, NumericType.INT, result, register));
          return result;
        }

        case FLOAT: {
          if (from == NumericType.DOUBLE) {
            break; // Not a widening conversion.
          }
          int result = nextRegister(ValueType.FLOAT);
          NumericType type = (from == NumericType.LONG) ? NumericType.LONG : NumericType.INT;
          add(builder -> builder.addConversion(to, type, result, register));
          return result;
        }

        case DOUBLE: {
          int result = nextRegister(ValueType.DOUBLE);
          NumericType type = (from == NumericType.FLOAT || from == NumericType.LONG)
              ? from : NumericType.INT;
          add(builder -> builder.addConversion(to, type, result, register));
          return result;
        }
        default:
          // exception is thrown below
          break;
      }
    }

    throw new Unreachable("Type " + fromType.toSourceString() + " cannot be " +
        "converted to " + toType.toSourceString() + " via primitive widening conversion.");
  }

  private DexMethod getUnboxMethod(byte primitive, DexType boxType) {
    DexItemFactory factory = factory();
    DexProto proto;
    switch (primitive) {
      case 'Z':  // byte
        proto = factory.createProto(factory.booleanType);
        return factory.createMethod(boxType, proto, factory.unboxBooleanMethodName);
      case 'B':  // byte
        proto = factory.createProto(factory.byteType);
        return factory.createMethod(boxType, proto, factory.unboxByteMethodName);
      case 'S':  // short
        proto = factory.createProto(factory.shortType);
        return factory.createMethod(boxType, proto, factory.unboxShortMethodName);
      case 'C':  // char
        proto = factory.createProto(factory.charType);
        return factory.createMethod(boxType, proto, factory.unboxCharMethodName);
      case 'I':  // int
        proto = factory.createProto(factory.intType);
        return factory.createMethod(boxType, proto, factory.unboxIntMethodName);
      case 'J':  // long
        proto = factory.createProto(factory.longType);
        return factory.createMethod(boxType, proto, factory.unboxLongMethodName);
      case 'F':  // float
        proto = factory.createProto(factory.floatType);
        return factory.createMethod(boxType, proto, factory.unboxFloatMethodName);
      case 'D':  // double
        proto = factory.createProto(factory.doubleType);
        return factory.createMethod(boxType, proto, factory.unboxDoubleMethodName);
      default:
        throw new Unreachable("Invalid primitive type descriptor: " + primitive);
    }
  }

  private int addPrimitiveUnboxing(int register, DexType primitiveType, DexType boxType) {
    DexMethod method = getUnboxMethod(primitiveType.descriptor.content[0], boxType);

    List<ValueType> argValueTypes = Collections.singletonList(ValueType.OBJECT);
    List<Integer> argRegisters = Collections.singletonList(register);
    add(builder -> builder.addInvoke(Invoke.Type.VIRTUAL,
        method, method.proto, argValueTypes, argRegisters));

    ValueType valueType = ValueType.fromDexType(primitiveType);
    int result = nextRegister(valueType);
    add(builder -> builder.addMoveResult(result));
    return result;
  }

  private int castToBoxedType(int register, DexType boxType) {
    add(builder -> builder.addCheckCast(register, boxType));
    return register;
  }

  private int addPrimitiveBoxing(int register, DexType primitiveType, DexType boxType) {
    // Generate factory method fo boxing.
    DexItemFactory factory = factory();
    DexProto proto = factory.createProto(boxType, primitiveType);
    DexMethod method = factory.createMethod(boxType, proto, factory.valueOfMethodName);

    ValueType valueType = ValueType.fromDexType(primitiveType);
    List<ValueType> argValueTypes = Collections.singletonList(valueType);
    List<Integer> argRegisters = Collections.singletonList(register);
    add(builder -> builder.addInvoke(Invoke.Type.STATIC,
        method, method.proto, argValueTypes, argRegisters));

    int result = nextRegister(ValueType.OBJECT);
    add(builder -> builder.addMoveResult(result));
    return result;
  }
}
