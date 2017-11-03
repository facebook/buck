// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexApplication.Builder;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeCustom;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.code.MemberType;
import com.android.tools.r8.ir.code.NewInstance;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.IRConverter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * Lambda desugaring rewriter.
 *
 * Performs lambda instantiation point matching,
 * lambda class generation, and instruction patching.
 */
public class LambdaRewriter {
  private static final String METAFACTORY_TYPE_DESCR = "Ljava/lang/invoke/LambdaMetafactory;";
  private static final String CALLSITE_TYPE_DESCR = "Ljava/lang/invoke/CallSite;";
  private static final String LOOKUP_TYPE_DESCR = "Ljava/lang/invoke/MethodHandles$Lookup;";
  private static final String METHODTYPE_TYPE_DESCR = "Ljava/lang/invoke/MethodType;";
  private static final String METHODHANDLE_TYPE_DESCR = "Ljava/lang/invoke/MethodHandle;";
  private static final String SERIALIZABLE_TYPE_DESCR = "Ljava/io/Serializable;";
  private static final String SERIALIZED_LAMBDA_TYPE_DESCR = "Ljava/lang/invoke/SerializedLambda;";

  private static final String METAFACTORY_METHOD_NAME = "metafactory";
  private static final String METAFACTORY_ALT_METHOD_NAME = "altMetafactory";
  private static final String DESERIALIZE_LAMBDA_METHOD_NAME = "$deserializeLambda$";

  // Public for testing.
  public static final String LAMBDA_CLASS_NAME_PREFIX = "-$$Lambda$";
  static final String EXPECTED_LAMBDA_METHOD_PREFIX = "lambda$";
  static final String LAMBDA_INSTANCE_FIELD_NAME = "INSTANCE";

  final IRConverter converter;
  final AppInfo appInfo;
  final DexItemFactory factory;

  final DexMethod metafactoryMethod;
  final DexMethod objectInitMethod;

  final DexMethod metafactoryAltMethod;
  final DexType serializableType;

  final DexString constructorName;
  final DexString classConstructorName;
  final DexString instanceFieldName;

  final DexString deserializeLambdaMethodName;
  final DexProto deserializeLambdaMethodProto;

  // Maps call sites seen so far to inferred lambda descriptor. It is intended
  // to help avoid re-matching call sites we already seen. Note that same call
  // site may match one or several lambda classes.
  //
  // NOTE: synchronize concurrent access on `knownCallSites`.
  private final Map<DexCallSite, LambdaDescriptor> knownCallSites = new IdentityHashMap<>();
  // Maps lambda class type into lambda class representation. Since lambda class
  // type uniquely defines lambda class, effectively canonicalizes lambda classes.
  // NOTE: synchronize concurrent access on `knownLambdaClasses`.
  private final Map<DexType, LambdaClass> knownLambdaClasses = new IdentityHashMap<>();

  // Checks if the type starts with lambda-class prefix.
  public static boolean hasLambdaClassPrefix(DexType clazz) {
    return clazz.getName().startsWith(LAMBDA_CLASS_NAME_PREFIX);
  }

  public LambdaRewriter(IRConverter converter) {
    assert converter != null;
    this.converter = converter;
    this.factory = converter.appInfo.dexItemFactory;
    this.appInfo = converter.appInfo;

    DexType metafactoryType = factory.createType(METAFACTORY_TYPE_DESCR);
    DexType callSiteType = factory.createType(CALLSITE_TYPE_DESCR);
    DexType lookupType = factory.createType(LOOKUP_TYPE_DESCR);
    DexType methodTypeType = factory.createType(METHODTYPE_TYPE_DESCR);
    DexType methodHandleType = factory.createType(METHODHANDLE_TYPE_DESCR);

    this.metafactoryMethod = factory.createMethod(metafactoryType,
        factory.createProto(callSiteType, lookupType, factory.stringType, methodTypeType,
            methodTypeType, methodHandleType, methodTypeType),
        factory.createString(METAFACTORY_METHOD_NAME));

    this.metafactoryAltMethod = factory.createMethod(metafactoryType,
        factory.createProto(callSiteType, lookupType, factory.stringType, methodTypeType,
            factory.objectArrayType),
        factory.createString(METAFACTORY_ALT_METHOD_NAME));

    this.constructorName = factory.createString(Constants.INSTANCE_INITIALIZER_NAME);
    DexProto initProto = factory.createProto(factory.voidType);
    this.objectInitMethod = factory.createMethod(factory.objectType, initProto, constructorName);
    this.classConstructorName = factory.createString(Constants.CLASS_INITIALIZER_NAME);
    this.instanceFieldName = factory.createString(LAMBDA_INSTANCE_FIELD_NAME);
    this.serializableType = factory.createType(SERIALIZABLE_TYPE_DESCR);

    this.deserializeLambdaMethodName = factory.createString(DESERIALIZE_LAMBDA_METHOD_NAME);
    this.deserializeLambdaMethodProto = factory.createProto(
        factory.objectType, factory.createType(SERIALIZED_LAMBDA_TYPE_DESCR));
  }

  /**
   * Detect and desugar lambdas and method references found in the code.
   *
   * NOTE: this method can be called concurrently for several different methods.
   */
  public void desugarLambdas(DexEncodedMethod encodedMethod, IRCode code) {
    DexType currentType = encodedMethod.method.holder;
    ListIterator<BasicBlock> blocks = code.listIterator();
    while (blocks.hasNext()) {
      BasicBlock block = blocks.next();
      InstructionListIterator instructions = block.listIterator();
      while (instructions.hasNext()) {
        Instruction instruction = instructions.next();
        if (instruction.isInvokeCustom()) {
          LambdaDescriptor descriptor = inferLambdaDescriptor(
              instruction.asInvokeCustom().getCallSite());
          if (descriptor == LambdaDescriptor.MATCH_FAILED) {
            continue;
          }

          // We have a descriptor, get or create lambda class.
          LambdaClass lambdaClass = getOrCreateLambdaClass(descriptor, currentType);
          assert lambdaClass != null;

          // We rely on patch performing its work in a way which
          // keeps both `instructions` and `blocks` iterators in
          // valid state so that we can continue iteration.
          patchInstruction(lambdaClass, code, blocks, instructions);
        }
      }
    }
  }

  /** Remove lambda deserialization methods. */
  public void removeLambdaDeserializationMethods(Iterable<DexProgramClass> classes) {
    for (DexProgramClass clazz : classes) {
      // Search for a lambda deserialization method and remove it if found.
      DexEncodedMethod[] directMethods = clazz.directMethods();
      if (directMethods != null) {
        int methodCount = directMethods.length;
        for (int i = 0; i < methodCount; i++) {
          DexEncodedMethod encoded = directMethods[i];
          DexMethod method = encoded.method;
          if (method.name == deserializeLambdaMethodName &&
              method.proto == deserializeLambdaMethodProto) {
            assert encoded.accessFlags.isStatic();
            assert encoded.accessFlags.isPrivate();
            assert encoded.accessFlags.isSynthetic();

            DexEncodedMethod[] newMethods = new DexEncodedMethod[methodCount - 1];
            System.arraycopy(directMethods, 0, newMethods, 0, i);
            System.arraycopy(directMethods, i + 1, newMethods, i, methodCount - i - 1);
            clazz.setDirectMethods(newMethods);

            // We assume there is only one such method in the class.
            break;
          }
        }
      }
    }
  }

  /**
   * Adjust accessibility of referenced application symbols or
   * creates necessary accessors.
   */
  public void adjustAccessibility() throws ApiLevelException {
    // For each lambda class perform necessary adjustment of the
    // referenced symbols to make them accessible. This can result in
    // method access relaxation or creation of accessor method.
    for (LambdaClass lambdaClass : knownLambdaClasses.values()) {
      lambdaClass.target.ensureAccessibility();
    }
  }

  /** Generates lambda classes and adds them to the builder. */
  public void synthesizeLambdaClasses(Builder<?> builder) throws ApiLevelException {
    for (LambdaClass lambdaClass : knownLambdaClasses.values()) {
      DexProgramClass synthesizedClass = lambdaClass.synthesizeLambdaClass();
      converter.optimizeSynthesizedClass(synthesizedClass);
      builder.addSynthesizedClass(synthesizedClass, lambdaClass.addToMainDexList.get());
    }
  }

  // Matches invoke-custom instruction operands to infer lambda descriptor
  // corresponding to this lambda invocation point.
  //
  // Returns the lambda descriptor or `MATCH_FAILED`.
  private LambdaDescriptor inferLambdaDescriptor(DexCallSite callSite) {
    // We check the map before and after inferring lambda descriptor to minimize time
    // spent in synchronized block. As a result we may throw away calculated descriptor
    // in rare case when another thread has same call site processed concurrently,
    // but this is a low price to pay comparing to making whole method synchronous.
    LambdaDescriptor descriptor = getKnown(knownCallSites, callSite);
    return descriptor != null ? descriptor
        : putIfAbsent(knownCallSites, callSite, LambdaDescriptor.infer(this, callSite));
  }

  private boolean isInMainDexList(DexType type) {
    return converter.appInfo.isInMainDexList(type);
  }

  // Returns a lambda class corresponding to the lambda descriptor and context,
  // creates the class if it does not yet exist.
  private LambdaClass getOrCreateLambdaClass(LambdaDescriptor descriptor, DexType accessedFrom) {
    DexType lambdaClassType = LambdaClass.createLambdaClassType(this, accessedFrom, descriptor);
    // We check the map twice to to minimize time spent in synchronized block.
    LambdaClass lambdaClass = getKnown(knownLambdaClasses, lambdaClassType);
    if (lambdaClass == null) {
      lambdaClass = putIfAbsent(knownLambdaClasses, lambdaClassType,
          new LambdaClass(this, accessedFrom, lambdaClassType, descriptor));
    }
    lambdaClass.addSynthesizedFrom(appInfo.definitionFor(accessedFrom).asProgramClass());
    if (isInMainDexList(accessedFrom)) {
      lambdaClass.addToMainDexList.set(true);
    }
    return lambdaClass;
  }

  private <K, V> V getKnown(Map<K, V> map, K key) {
    synchronized (map) {
      return map.get(key);
    }
  }

  private <K, V> V putIfAbsent(Map<K, V> map, K key, V value) {
    synchronized (map) {
      V known = map.get(key);
      if (known != null) {
        return known;
      }
      map.put(key, value);
      return value;
    }
  }

  // Patches invoke-custom instruction to create or get an instance
  // of the generated lambda class.
  private void patchInstruction(LambdaClass lambdaClass, IRCode code,
      ListIterator<BasicBlock> blocks, InstructionListIterator instructions) {
    assert lambdaClass != null;
    assert instructions != null;
    assert instructions.peekPrevious().isInvokeCustom();

    // Move to the previous instruction, must be InvokeCustom
    InvokeCustom invoke = instructions.previous().asInvokeCustom();

    // The value representing new lambda instance: we reuse the
    // value from the original invoke-custom instruction, and thus
    // all its usages.
    Value lambdaInstanceValue = invoke.outValue();
    if (lambdaInstanceValue == null) {
      // The out value might be empty in case it was optimized out.
      lambdaInstanceValue = code.createValue(ValueType.OBJECT);
    }

    // For stateless lambdas we replace InvokeCustom instruction with StaticGet
    // reading the value of INSTANCE field created for singleton lambda class.
    if (lambdaClass.isStateless()) {
      instructions.replaceCurrentInstruction(
          new StaticGet(MemberType.OBJECT, lambdaInstanceValue, lambdaClass.instanceField));
      // Note that since we replace one throwing operation with another we don't need
      // to have any special handling for catch handlers.
      return;
    }

    // For stateful lambdas we always create a new instance since we need to pass
    // captured values to the constructor.
    //
    // We replace InvokeCustom instruction with a new NewInstance instruction
    // instantiating lambda followed by InvokeDirect instruction calling a
    // constructor on it.
    //
    //    original:
    //      Invoke-Custom rResult <- { rArg0, rArg1, ... }; call site: ...
    //
    //    result:
    //      NewInstance   rResult <-  LambdaClass
    //      Invoke-Direct { rResult, rArg0, rArg1, ... }; method: void LambdaClass.<init>(...)
    NewInstance newInstance = new NewInstance(lambdaClass.type, lambdaInstanceValue);
    instructions.replaceCurrentInstruction(newInstance);

    List<Value> arguments = new ArrayList<>();
    arguments.add(lambdaInstanceValue);
    arguments.addAll(invoke.arguments()); // Optional captures.
    InvokeDirect constructorCall = new InvokeDirect(
        lambdaClass.constructor, null /* no return value */, arguments);
    instructions.add(constructorCall);
    constructorCall.setPosition(newInstance.getPosition());

    // If we don't have catch handlers we are done.
    if (!constructorCall.getBlock().hasCatchHandlers()) {
      return;
    }

    // Move the iterator back to position it between the two instructions, split
    // the block between the two instructions, and copy the catch handlers.
    instructions.previous();
    assert instructions.peekNext().isInvokeDirect();
    BasicBlock currentBlock = newInstance.getBlock();
    BasicBlock nextBlock = instructions.split(code, blocks);
    assert !instructions.hasNext();
    nextBlock.copyCatchHandlers(code, blocks, currentBlock);
  }
}
