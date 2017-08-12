// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexAnnotationSetRefList;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexCode;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.DexValue.DexValueNull;
import com.android.tools.r8.graph.FieldAccessFlags;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.synthetic.SynthesizedCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents lambda class generated for a lambda descriptor in context
 * of lambda instantiation point.
 *
 * Even though call sites, and thus lambda descriptors, are canonicalized
 * across the application, the context may require several lambda classes
 * to be generated for the same lambda descriptor.
 *
 * One reason is that we always generate a lambda class in the same package
 * lambda instantiation point is located in, so if same call site is used in
 * two classes from different packages (which can happen if same public method
 * is being references via method reference expression) we generate separate
 * lambda classes in those packages.
 *
 * Another reason is that if we generate an accessor, we generate it in the
 * class referencing the call site, and thus two such classes will require two
 * separate lambda classes.
 */
final class LambdaClass {

  final LambdaRewriter rewriter;
  final DexType type;
  final LambdaDescriptor descriptor;
  final DexMethod constructor;
  final DexMethod classConstructor;
  final DexField instanceField;
  final Target target;
  final AtomicBoolean addToMainDexList = new AtomicBoolean(false);
  private Collection<DexProgramClass> synthesizedFrom = new ArrayList<DexProgramClass>(1);

  LambdaClass(LambdaRewriter rewriter, DexType accessedFrom,
      DexType lambdaClassType, LambdaDescriptor descriptor) {
    assert rewriter != null;
    assert lambdaClassType != null;
    assert descriptor != null;

    this.rewriter = rewriter;
    this.type = lambdaClassType;
    this.descriptor = descriptor;

    DexItemFactory factory = rewriter.factory;
    DexProto constructorProto = factory.createProto(
        factory.voidType, descriptor.captures.values);
    this.constructor = factory.createMethod(
        lambdaClassType, constructorProto, rewriter.constructorName);

    this.target = createTarget(accessedFrom);

    boolean stateless = isStateless();
    this.classConstructor = !stateless ? null
        : factory.createMethod(lambdaClassType, constructorProto, rewriter.classConstructorName);
    this.instanceField = !stateless ? null
        : factory.createField(lambdaClassType, lambdaClassType, rewriter.instanceFieldName);

    // We have to register this new class as a subtype of object.
    rewriter.appInfo.registerNewType(type, factory.objectType);
  }

  // Generate unique lambda class type for lambda descriptor and instantiation point context.
  static DexType createLambdaClassType(
      LambdaRewriter rewriter, DexType accessedFrom, LambdaDescriptor match) {
    StringBuilder lambdaClassDescriptor = new StringBuilder("L");

    // We always create lambda class in the same package where it is referenced.
    String packageDescriptor = accessedFrom.getPackageDescriptor();
    if (!packageDescriptor.isEmpty()) {
      lambdaClassDescriptor.append(packageDescriptor).append('/');
    }

    // Lambda class name prefix
    lambdaClassDescriptor.append(LambdaRewriter.LAMBDA_CLASS_NAME_PREFIX);

    // If the lambda class should match 1:1 the class it is accessed from, we
    // just add the name of this type to make lambda class name unique.
    // It also helps link the class lambda originated from in some cases.
    if (match.delegatesToLambdaImplMethod() || match.needsAccessor(accessedFrom)) {
      lambdaClassDescriptor.append(accessedFrom.getName()).append('$');
    }

    // Add unique lambda descriptor id
    lambdaClassDescriptor.append(match.uniqueId).append(';');
    return rewriter.factory.createType(lambdaClassDescriptor.toString());
  }

  final DexProgramClass synthesizeLambdaClass() {
    return new DexProgramClass(
        type,
        null,
        null,
        ClassAccessFlags.fromDexAccessFlags(Constants.ACC_FINAL | Constants.ACC_SYNTHETIC),
        rewriter.factory.objectType,
        buildInterfaces(),
        rewriter.factory.createString("lambda"),
        DexAnnotationSet.empty(),
        synthesizeStaticFields(),
        synthesizeInstanceFields(),
        synthesizeDirectMethods(),
        synthesizeVirtualMethods(),
        synthesizedFrom);
  }

  final DexField getCaptureField(int index) {
    return rewriter.factory.createField(this.type,
        descriptor.captures.values[index], rewriter.factory.createString("f$" + index));
  }

  final boolean isStateless() {
    return descriptor.isStateless();
  }

  synchronized void addSynthesizedFrom(DexProgramClass synthesizedFrom) {
    assert synthesizedFrom != null;
    this.synthesizedFrom.add(synthesizedFrom);
  }

  // Synthesize virtual methods.
  private DexEncodedMethod[] synthesizeVirtualMethods() {
    DexEncodedMethod[] methods = new DexEncodedMethod[1 + descriptor.bridges.size()];
    int index = 0;

    // Synthesize main method.
    DexMethod mainMethod = rewriter.factory
        .createMethod(type, descriptor.erasedProto, descriptor.name);
    methods[index++] =
        new DexEncodedMethod(
            mainMethod,
            MethodAccessFlags.fromSharedAccessFlags(
                Constants.ACC_PUBLIC | Constants.ACC_FINAL, false),
            DexAnnotationSet.empty(),
            DexAnnotationSetRefList.empty(),
            new SynthesizedCode(new LambdaMainMethodSourceCode(this, mainMethod)));

    // Synthesize bridge methods.
    for (DexProto bridgeProto : descriptor.bridges) {
      DexMethod bridgeMethod = rewriter.factory.createMethod(type, bridgeProto, descriptor.name);
      methods[index++] =
          new DexEncodedMethod(
              bridgeMethod,
              MethodAccessFlags.fromSharedAccessFlags(
                  Constants.ACC_PUBLIC
                      | Constants.ACC_FINAL
                      | Constants.ACC_SYNTHETIC
                      | Constants.ACC_BRIDGE,
                  false),
              DexAnnotationSet.empty(),
              DexAnnotationSetRefList.empty(),
              new SynthesizedCode(
                  new LambdaBridgeMethodSourceCode(this, mainMethod, bridgeMethod)));
    }
    return methods;
  }

  // Synthesize direct methods.
  private DexEncodedMethod[] synthesizeDirectMethods() {
    boolean stateless = isStateless();
    DexEncodedMethod[] methods = new DexEncodedMethod[stateless ? 2 : 1];

    // Constructor.
    methods[0] =
        new DexEncodedMethod(
            constructor,
            MethodAccessFlags.fromSharedAccessFlags(
                (stateless ? Constants.ACC_PRIVATE : Constants.ACC_PUBLIC)
                    | Constants.ACC_SYNTHETIC,
                true),
            DexAnnotationSet.empty(),
            DexAnnotationSetRefList.empty(),
            new SynthesizedCode(new LambdaConstructorSourceCode(this)));

    // Class constructor for stateless lambda classes.
    if (stateless) {
      methods[1] =
          new DexEncodedMethod(
              classConstructor,
              MethodAccessFlags.fromSharedAccessFlags(
                  Constants.ACC_SYNTHETIC | Constants.ACC_STATIC, true),
              DexAnnotationSet.empty(),
              DexAnnotationSetRefList.empty(),
              new SynthesizedCode(new LambdaClassConstructorSourceCode(this)));
    }
    return methods;
  }

  // Synthesize instance fields to represent captured values.
  private DexEncodedField[] synthesizeInstanceFields() {
    DexType[] fieldTypes = descriptor.captures.values;
    int fieldCount = fieldTypes.length;
    DexEncodedField[] fields = new DexEncodedField[fieldCount];
    for (int i = 0; i < fieldCount; i++) {
      FieldAccessFlags accessFlags =
          FieldAccessFlags.fromSharedAccessFlags(
              Constants.ACC_FINAL | Constants.ACC_SYNTHETIC | Constants.ACC_PRIVATE);
      fields[i] = new DexEncodedField(
          getCaptureField(i), accessFlags, DexAnnotationSet.empty(), null);
    }
    return fields;
  }

  // Synthesize static fields to represent singleton instance.
  private DexEncodedField[] synthesizeStaticFields() {
    if (!isStateless()) {
      return DexEncodedField.EMPTY_ARRAY;
    }

    // Create instance field for stateless lambda.
    assert this.instanceField != null;
    DexEncodedField[] fields = new DexEncodedField[1];
    fields[0] =
        new DexEncodedField(
            this.instanceField,
            FieldAccessFlags.fromSharedAccessFlags(
                Constants.ACC_PUBLIC
                    | Constants.ACC_FINAL
                    | Constants.ACC_SYNTHETIC
                    | Constants.ACC_STATIC),
            DexAnnotationSet.empty(),
            DexValueNull.NULL);
    return fields;
  }

  // Build a list of implemented interfaces.
  private DexTypeList buildInterfaces() {
    List<DexType> interfaces = descriptor.interfaces;
    return interfaces.isEmpty() ? DexTypeList.empty()
        : new DexTypeList(interfaces.toArray(new DexType[interfaces.size()]));
  }

  // Creates a delegation target for this particular lambda class. Note that we
  // should always be able to create targets for the lambdas we support.
  private Target createTarget(DexType accessedFrom) {
    if (descriptor.delegatesToLambdaImplMethod()) {
      return createLambdaImplMethodTarget(accessedFrom);
    }

    // Method referenced directly, without lambda$ method.
    switch (descriptor.implHandle.type) {
      case INVOKE_SUPER:
        throw new Unimplemented("Method references to super methods are not yet supported");
      case INVOKE_INTERFACE:
        return createInterfaceMethodTarget(accessedFrom);
      case INVOKE_CONSTRUCTOR:
        return createConstructorTarget(accessedFrom);
      case INVOKE_STATIC:
        return createStaticMethodTarget(accessedFrom);
      case INVOKE_DIRECT:
      case INVOKE_INSTANCE:
        return createInstanceMethodTarget(accessedFrom);
      default:
        throw new Unreachable("Unexpected method handle type in " + descriptor.implHandle);
    }
  }

  private Target createLambdaImplMethodTarget(DexType accessedFrom) {
    DexMethodHandle implHandle = descriptor.implHandle;
    assert implHandle != null;
    DexMethod implMethod = implHandle.asMethod();

    // Lambda$ method. We must always find it.
    assert implMethod.holder == accessedFrom;
    assert descriptor.targetFoundInClass(accessedFrom);
    assert descriptor.getAccessibility() != null;
    assert descriptor.getAccessibility().isPrivate();
    assert descriptor.getAccessibility().isSynthetic();

    if (implHandle.type.isInvokeStatic()) {
      return new StaticLambdaImplTarget();
    }

    assert implHandle.type.isInvokeInstance() || implHandle.type.isInvokeDirect();

    // If lambda$ method is an instance method we convert it into a static methods and
    // relax its accessibility.
    DexProto implProto = implMethod.proto;
    DexType[] implParams = implProto.parameters.values;
    DexType[] newParams = new DexType[implParams.length + 1];
    newParams[0] = implMethod.holder;
    System.arraycopy(implParams, 0, newParams, 1, implParams.length);

    DexProto newProto = rewriter.factory.createProto(implProto.returnType, newParams);
    return new InstanceLambdaImplTarget(
        rewriter.factory.createMethod(implMethod.holder, newProto, implMethod.name));
  }

  // Create targets for instance method referenced directly without
  // lambda$ methods. It may require creation of accessors in some cases.
  private Target createInstanceMethodTarget(DexType accessedFrom) {
    assert descriptor.implHandle.type.isInvokeInstance() ||
        descriptor.implHandle.type.isInvokeDirect();

    if (!descriptor.needsAccessor(accessedFrom)) {
      return new NoAccessorMethodTarget(Invoke.Type.VIRTUAL);
    }
    // We need to generate an accessor method in `accessedFrom` class/interface
    // for accessing the original instance impl-method. Note that impl-method's
    // holder does not have to be the same as `accessedFrom`.
    DexMethod implMethod = descriptor.implHandle.asMethod();
    DexProto implProto = implMethod.proto;
    DexType[] implParams = implProto.parameters.values;

    // The accessor method will be static, package private, and take the
    // receiver as the first argument. The receiver must be captured and
    // be the first captured value in case there are more than one.
    DexType[] accessorParams = new DexType[1 + implParams.length];
    accessorParams[0] = descriptor.getImplReceiverType();
    System.arraycopy(implParams, 0, accessorParams, 1, implParams.length);
    DexProto accessorProto = rewriter.factory.createProto(implProto.returnType, accessorParams);
    DexMethod accessorMethod = rewriter.factory.createMethod(
        accessedFrom, accessorProto, generateUniqueLambdaMethodName());

    return new ClassMethodWithAccessorTarget(accessorMethod);
  }

  // Create targets for static method referenced directly without
  // lambda$ methods. It may require creation of accessors in some cases.
  private Target createStaticMethodTarget(DexType accessedFrom) {
    assert descriptor.implHandle.type.isInvokeStatic();

    if (!descriptor.needsAccessor(accessedFrom)) {
      return new NoAccessorMethodTarget(Invoke.Type.STATIC);
    }

    // We need to generate an accessor method in `accessedFrom` class/interface
    // for accessing the original static impl-method. The accessor method will be
    // static, package private with exactly same signature and the original method.
    DexMethod accessorMethod = rewriter.factory.createMethod(accessedFrom,
        descriptor.implHandle.asMethod().proto, generateUniqueLambdaMethodName());
    return new ClassMethodWithAccessorTarget(accessorMethod);
  }

  // Create targets for constructor referenced directly without lambda$ methods.
  // It may require creation of accessors in some cases.
  private Target createConstructorTarget(DexType accessedFrom) {
    DexMethodHandle implHandle = descriptor.implHandle;
    assert implHandle != null;
    assert implHandle.type.isInvokeConstructor();

    if (!descriptor.needsAccessor(accessedFrom)) {
      return new NoAccessorMethodTarget(Invoke.Type.DIRECT);
    }

    // We need to generate an accessor method in `accessedFrom` class/interface for
    // instantiating the class and calling constructor on it. The accessor method will
    // be static, package private with exactly same parameters as the constructor,
    // and return the newly created instance.
    DexMethod implMethod = implHandle.asMethod();
    DexType returnType = implMethod.holder;
    DexProto accessorProto = rewriter.factory.createProto(
        returnType, implMethod.proto.parameters.values);
    DexMethod accessorMethod = rewriter.factory.createMethod(accessedFrom,
        accessorProto, generateUniqueLambdaMethodName());
    return new ClassMethodWithAccessorTarget(accessorMethod);
  }

  // Create targets for interface methods.
  private Target createInterfaceMethodTarget(DexType accessedFrom) {
    assert descriptor.implHandle.type.isInvokeInterface();
    assert !descriptor.needsAccessor(accessedFrom);
    return new NoAccessorMethodTarget(Invoke.Type.INTERFACE);
  }

  private DexString generateUniqueLambdaMethodName() {
    return rewriter.factory.createString(
        LambdaRewriter.EXPECTED_LAMBDA_METHOD_PREFIX + descriptor.uniqueId);
  }

  // Represents information about the method lambda class need to delegate the call to. It may
  // be the same method as specified in lambda descriptor or a newly synthesized accessor.
  // Also provides action for ensuring accessibility of the referenced symbols.
  abstract class Target {

    final DexMethod callTarget;
    final Invoke.Type invokeType;

    Target(DexMethod callTarget, Invoke.Type invokeType) {
      assert callTarget != null;
      assert invokeType != null;
      this.callTarget = callTarget;
      this.invokeType = invokeType;
    }

    // Ensure access of the referenced symbol(s).
    abstract boolean ensureAccessibility() throws ApiLevelException;

    DexClass definitionFor(DexType type) {
      return rewriter.converter.appInfo.app.definitionFor(type);
    }

    DexProgramClass programDefinitionFor(DexType type) {
      return rewriter.converter.appInfo.app.programDefinitionFor(type);
    }
  }

  // Used for targeting methods referenced directly without creating accessors.
  private final class NoAccessorMethodTarget extends Target {

    NoAccessorMethodTarget(Invoke.Type invokeType) {
      super(descriptor.implHandle.asMethod(), invokeType);
    }

    @Override
    boolean ensureAccessibility() {
      return true;
    }
  }

  // Used for static private lambda$ methods. Only needs access relaxation.
  private final class StaticLambdaImplTarget extends Target {

    StaticLambdaImplTarget() {
      super(descriptor.implHandle.asMethod(), Invoke.Type.STATIC);
    }

    @Override
    boolean ensureAccessibility() {
      // We already found the static method to be called, just relax its accessibility.
      assert descriptor.getAccessibility() != null;
      descriptor.getAccessibility().unsetPrivate();
      DexClass implMethodHolder = definitionFor(descriptor.implHandle.asMethod().holder);
      if (implMethodHolder.isInterface()) {
        descriptor.getAccessibility().setPublic();
      }
      return true;
    }
  }

  // Used for instance private lambda$ methods. Needs to be converted to
  // a package-private static method.
  private class InstanceLambdaImplTarget extends Target {

    InstanceLambdaImplTarget(DexMethod staticMethod) {
      super(staticMethod, Invoke.Type.STATIC);
    }

    @Override
    boolean ensureAccessibility() {
      // For all instantiation points for which compiler creates lambda$
      // methods, it creates these methods in the same class/interface.
      DexMethod implMethod = descriptor.implHandle.asMethod();
      DexClass implMethodHolder = definitionFor(implMethod.holder);

      DexEncodedMethod[] directMethods = implMethodHolder.directMethods();
      for (int i = 0; i < directMethods.length; i++) {
        DexEncodedMethod encodedMethod = directMethods[i];
        if (implMethod.match(encodedMethod)) {
          // We need to create a new static method with the same code to be able to safely
          // relax its accessibility without making it virtual.
          DexEncodedMethod newMethod = new DexEncodedMethod(
              callTarget, encodedMethod.accessFlags, encodedMethod.annotations,
              encodedMethod.parameterAnnotations, encodedMethod.getCode());
          // TODO(ager): Should we give the new first parameter an actual name? Maybe 'this'?
          encodedMethod.accessFlags.setStatic();
          encodedMethod.accessFlags.unsetPrivate();
          if (implMethodHolder.isInterface()) {
            encodedMethod.accessFlags.setPublic();
          }
          DexCode dexCode = newMethod.getCode().asDexCode();
          dexCode.setDebugInfo(dexCode.debugInfoWithAdditionalFirstParameter(null));
          assert (dexCode.getDebugInfo() == null)
              || (callTarget.getArity() == dexCode.getDebugInfo().parameters.length);
          directMethods[i] = newMethod;
          return true;
        }
      }
      return false;
    }
  }

  // Used for instance/static methods or constructors accessed via
  // synthesized accessor method. Needs accessor method to be created.
  private class ClassMethodWithAccessorTarget extends Target {

    ClassMethodWithAccessorTarget(DexMethod accessorMethod) {
      super(accessorMethod, Invoke.Type.STATIC);
    }

    @Override
    boolean ensureAccessibility() throws ApiLevelException {
      // Create a static accessor with proper accessibility.
      DexProgramClass accessorClass = programDefinitionFor(callTarget.holder);
      assert accessorClass != null;

      MethodAccessFlags accessorFlags =
          MethodAccessFlags.fromSharedAccessFlags(
              Constants.ACC_SYNTHETIC
                  | Constants.ACC_STATIC
                  | (accessorClass.isInterface() ? Constants.ACC_PUBLIC : 0),
              false);
      DexEncodedMethod accessorEncodedMethod = new DexEncodedMethod(
          callTarget, accessorFlags, DexAnnotationSet.empty(), DexAnnotationSetRefList.empty(),
          new SynthesizedCode(new AccessorMethodSourceCode(LambdaClass.this)));
      accessorClass.setDirectMethods(appendMethod(
          accessorClass.directMethods(), accessorEncodedMethod));
      rewriter.converter.optimizeSynthesizedMethod(accessorEncodedMethod);
      return true;
    }

    private DexEncodedMethod[] appendMethod(DexEncodedMethod[] methods, DexEncodedMethod method) {
      int size = methods.length;
      DexEncodedMethod[] newMethods = new DexEncodedMethod[size + 1];
      System.arraycopy(methods, 0, newMethods, 0, size);
      newMethods[size] = method;
      return newMethods;
    }
  }
}
