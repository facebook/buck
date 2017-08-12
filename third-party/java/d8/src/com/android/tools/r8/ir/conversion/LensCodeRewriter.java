// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexMethodHandle.MethodHandleType;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.CheckCast;
import com.android.tools.r8.ir.code.ConstClass;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstanceGet;
import com.android.tools.r8.ir.code.InstanceOf;
import com.android.tools.r8.ir.code.InstancePut;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.ir.code.InvokeCustom;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeNewArray;
import com.android.tools.r8.ir.code.NewArrayEmpty;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.ir.code.Value;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

public class LensCodeRewriter {

  private final GraphLense graphLense;
  private final AppInfoWithSubtyping appInfo;

  public LensCodeRewriter(GraphLense graphLense, AppInfoWithSubtyping appInfo) {
    this.graphLense = graphLense;
    this.appInfo = appInfo;
  }

  private Value makeOutValue(Instruction insn, IRCode code) {
    if (insn.outValue() == null) {
      return null;
    } else {
      return code.createValue(insn.outType(), insn.getLocalInfo());
    }
  }

  /**
   * Replace invoke targets and field accesses with actual definitions.
   */
  public void rewrite(IRCode code, DexEncodedMethod method) {
    ListIterator<BasicBlock> blocks = code.blocks.listIterator();
    while (blocks.hasNext()) {
      BasicBlock block = blocks.next();
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        Instruction current = iterator.next();
        if (current.isInvokeCustom()) {
          InvokeCustom invokeCustom = current.asInvokeCustom();
          DexCallSite callSite = invokeCustom.getCallSite();
          DexMethodHandle newBootstrapMethod = rewriteDexMethodHandle(method,
              callSite.bootstrapMethod);
          List<DexValue> newArgs = callSite.bootstrapArgs.stream().map(
              (arg) -> {
                if (arg instanceof DexValueMethodHandle) {
                  return new DexValueMethodHandle(
                      rewriteDexMethodHandle(method, ((DexValueMethodHandle) arg).value));
                }
                return arg;
              })
              .collect(Collectors.toList());

          if (newBootstrapMethod != callSite.bootstrapMethod
              || !newArgs.equals(callSite.bootstrapArgs)) {
            DexCallSite newCallSite = appInfo.dexItemFactory.createCallSite(
                callSite.methodName, callSite.methodProto, newBootstrapMethod, newArgs);
            InvokeCustom newInvokeCustom = new InvokeCustom(newCallSite, invokeCustom.outValue(),
                invokeCustom.inValues());
            iterator.replaceCurrentInstruction(newInvokeCustom);
          }
        } else if (current.isInvokeMethod()) {
          InvokeMethod invoke = current.asInvokeMethod();
          DexMethod invokedMethod = invoke.getInvokedMethod();
          DexType invokedHolder = invokedMethod.getHolder();
          if (!invokedHolder.isClassType()) {
            continue;
          }
          DexMethod actualTarget = graphLense.lookupMethod(invokedMethod, method);
          Invoke.Type invokeType = getInvokeType(invoke, actualTarget, invokedMethod);
          if (actualTarget != invokedMethod || invoke.getType() != invokeType) {
            Invoke newInvoke = Invoke
                .create(invokeType, actualTarget, null,
                    invoke.outValue(), invoke.inValues());
            iterator.replaceCurrentInstruction(newInvoke);
            // Fix up the return type if needed.
            if (actualTarget.proto.returnType != invokedMethod.proto.returnType
                && newInvoke.outValue() != null) {
              Value newValue = code.createValue(newInvoke.outType(), invoke.getLocalInfo());
              newInvoke.outValue().replaceUsers(newValue);
              CheckCast cast = new CheckCast(
                  newValue,
                  newInvoke.outValue(),
                  graphLense.lookupType(invokedMethod.proto.returnType, method));
              cast.setPosition(current.getPosition());
              iterator.add(cast);
              // If the current block has catch handlers split the check cast into its own block.
              if (newInvoke.getBlock().hasCatchHandlers()) {
                iterator.previous();
                iterator.split(code, 1, blocks);
              }
            }
          }
        } else if (current.isInstanceGet()) {
          InstanceGet instanceGet = current.asInstanceGet();
          DexField field = instanceGet.getField();
          DexField actualField = graphLense.lookupField(field, method);
          if (actualField != field) {
            InstanceGet newInstanceGet =
                new InstanceGet(
                    instanceGet.getType(), instanceGet.dest(), instanceGet.object(), actualField);
            iterator.replaceCurrentInstruction(newInstanceGet);
          }
        } else if (current.isInstancePut()) {
          InstancePut instancePut = current.asInstancePut();
          DexField field = instancePut.getField();
          DexField actualField = graphLense.lookupField(field, method);
          if (actualField != field) {
            InstancePut newInstancePut =
                new InstancePut(instancePut.getType(), instancePut.inValues(), actualField);
            iterator.replaceCurrentInstruction(newInstancePut);
          }
        } else if (current.isStaticGet()) {
          StaticGet staticGet = current.asStaticGet();
          DexField field = staticGet.getField();
          DexField actualField = graphLense.lookupField(field, method);
          if (actualField != field) {
            StaticGet newStaticGet =
                new StaticGet(staticGet.getType(), staticGet.dest(), actualField);
            iterator.replaceCurrentInstruction(newStaticGet);
          }
        } else if (current.isStaticPut()) {
          StaticPut staticPut = current.asStaticPut();
          DexField field = staticPut.getField();
          DexField actualField = graphLense.lookupField(field, method);
          if (actualField != field) {
            StaticPut newStaticPut =
                new StaticPut(staticPut.getType(), staticPut.inValue(), actualField);
            iterator.replaceCurrentInstruction(newStaticPut);
          }
        } else if (current.isCheckCast()) {
          CheckCast checkCast = current.asCheckCast();
          DexType newType = graphLense.lookupType(checkCast.getType(), method);
          if (newType != checkCast.getType()) {
            CheckCast newCheckCast =
                new CheckCast(makeOutValue(checkCast, code), checkCast.object(), newType);
            iterator.replaceCurrentInstruction(newCheckCast);
          }
        } else if (current.isConstClass()) {
          ConstClass constClass = current.asConstClass();
          DexType newType = graphLense.lookupType(constClass.getValue(), method);
          if (newType != constClass.getValue()) {
            ConstClass newConstClass = new ConstClass(makeOutValue(constClass, code), newType);
            iterator.replaceCurrentInstruction(newConstClass);
          }
        } else if (current.isInstanceOf()) {
          InstanceOf instanceOf = current.asInstanceOf();
          DexType newType = graphLense.lookupType(instanceOf.type(), method);
          if (newType != instanceOf.type()) {
            InstanceOf newInstanceOf = new InstanceOf(makeOutValue(instanceOf, code),
                instanceOf.value(), newType);
            iterator.replaceCurrentInstruction(newInstanceOf);
          }
        } else if (current.isInvokeNewArray()) {
          InvokeNewArray newArray = current.asInvokeNewArray();
          DexType newType = graphLense.lookupType(newArray.getArrayType(), method);
          if (newType != newArray.getArrayType()) {
            InvokeNewArray newNewArray = new InvokeNewArray(newType, makeOutValue(newArray, code),
                newArray.inValues());
            iterator.replaceCurrentInstruction(newNewArray);
          }
        } else if (current.isNewArrayEmpty()) {
          NewArrayEmpty newArrayEmpty = current.asNewArrayEmpty();
          DexType newType = graphLense.lookupType(newArrayEmpty.type, method);
          if (newType != newArrayEmpty.type) {
            NewArrayEmpty newNewArray = new NewArrayEmpty(makeOutValue(newArrayEmpty, code),
                newArrayEmpty.size(), newType);
            iterator.replaceCurrentInstruction(newNewArray);
          }
        }
      }
    }
    assert code.isConsistentSSA();
  }

  private DexMethodHandle rewriteDexMethodHandle(
      DexEncodedMethod method, DexMethodHandle methodHandle) {
    if (methodHandle.isMethodHandle()) {
      DexMethod invokedMethod = methodHandle.asMethod();
      DexMethod actualTarget = graphLense.lookupMethod(invokedMethod, method);
      if (actualTarget != invokedMethod) {
        DexClass clazz = appInfo.definitionFor(actualTarget.holder);
        MethodHandleType newType = methodHandle.type;
        if (clazz != null
            && (newType.isInvokeInterface() || newType.isInvokeInstance())) {
          newType = clazz.accessFlags.isInterface()
              ? MethodHandleType.INVOKE_INTERFACE
              : MethodHandleType.INVOKE_INSTANCE;
        }
        return new DexMethodHandle(newType, actualTarget);
      }
    } else {
      DexField field = methodHandle.asField();
      DexField actualField = graphLense.lookupField(field, method);
      if (actualField != field) {
        return new DexMethodHandle(methodHandle.type, actualField);
      }
    }
    return methodHandle;
  }

  private Type getInvokeType(
      InvokeMethod invoke,
      DexMethod actualTarget,
      DexMethod originalTarget) {
    if (invoke.isInvokeVirtual() || invoke.isInvokeInterface()) {
      // Get the invoke type of the actual definition.
      DexClass newTargetClass = appInfo.definitionFor(actualTarget.holder);
      if (newTargetClass == null) {
        return invoke.getType();
      } else {
        DexClass originalTargetClass = appInfo.definitionFor(originalTarget.holder);
        if ((originalTargetClass != null && originalTargetClass.isInterface())
            ^ (invoke.getType() == Type.INTERFACE)) {
          // The invoke was wrong to start with, so we keep it wrong. This is to ensure we get
          // the IncompatibleClassChangeError the original invoke would have triggered.
          return newTargetClass.accessFlags.isInterface()
              ? Type.VIRTUAL
              : Type.INTERFACE;
        } else {
          return newTargetClass.accessFlags.isInterface()
              ? Type.INTERFACE
              : Type.VIRTUAL;
        }
      }
    } else {
      return invoke.getType();
    }
  }
}
