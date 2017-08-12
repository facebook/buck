// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.Marker;
import com.android.tools.r8.graph.DexDebugEvent.AdvanceLine;
import com.android.tools.r8.graph.DexDebugEvent.AdvancePC;
import com.android.tools.r8.graph.DexDebugEvent.Default;
import com.android.tools.r8.graph.DexDebugEvent.EndLocal;
import com.android.tools.r8.graph.DexDebugEvent.RestartLocal;
import com.android.tools.r8.graph.DexDebugEvent.SetEpilogueBegin;
import com.android.tools.r8.graph.DexDebugEvent.SetFile;
import com.android.tools.r8.graph.DexDebugEvent.SetPrologueEnd;
import com.android.tools.r8.graph.DexMethodHandle.MethodHandleType;
import com.android.tools.r8.naming.NamingLens;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class DexItemFactory {

  private final ConcurrentHashMap<DexString, DexString> strings = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DexString, DexType> types = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DexField, DexField> fields = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DexProto, DexProto> protos = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DexMethod, DexMethod> methods = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DexCallSite, DexCallSite> callSites = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DexMethodHandle, DexMethodHandle> methodHandles =
      new ConcurrentHashMap<>();

  // DexDebugEvent Canonicalization.
  private final Int2ObjectMap<AdvanceLine> advanceLines = new Int2ObjectOpenHashMap<>();
  private final Int2ObjectMap<AdvancePC> advancePCs = new Int2ObjectOpenHashMap<>();
  private final Int2ObjectMap<Default> defaults = new Int2ObjectOpenHashMap<>();
  private final Int2ObjectMap<EndLocal> endLocals = new Int2ObjectOpenHashMap<>();
  private final Int2ObjectMap<RestartLocal> restartLocals = new Int2ObjectOpenHashMap<>();
  private final SetEpilogueBegin setEpilogueBegin = new SetEpilogueBegin();
  private final SetPrologueEnd setPrologueEnd = new SetPrologueEnd();
  private final Map<DexString, SetFile> setFiles = new HashMap<>();

  boolean sorted = false;

  public static final DexType catchAllType = new DexType(new DexString("CATCH_ALL"));
  private static final Set<DexItem> internalSentinels = ImmutableSet.of(catchAllType);

  public final DexString booleanDescriptor = createString("Z");
  public final DexString byteDescriptor = createString("B");
  public final DexString charDescriptor = createString("C");
  public final DexString doubleDescriptor = createString("D");
  public final DexString floatDescriptor = createString("F");
  public final DexString intDescriptor = createString("I");
  public final DexString longDescriptor = createString("J");
  public final DexString shortDescriptor = createString("S");
  public final DexString voidDescriptor = createString("V");

  public final DexString boxedBooleanDescriptor = createString("Ljava/lang/Boolean;");
  public final DexString boxedByteDescriptor = createString("Ljava/lang/Byte;");
  public final DexString boxedCharDescriptor = createString("Ljava/lang/Character;");
  public final DexString boxedDoubleDescriptor = createString("Ljava/lang/Double;");
  public final DexString boxedFloatDescriptor = createString("Ljava/lang/Float;");
  public final DexString boxedIntDescriptor = createString("Ljava/lang/Integer;");
  public final DexString boxedLongDescriptor = createString("Ljava/lang/Long;");
  public final DexString boxedShortDescriptor = createString("Ljava/lang/Short;");
  public final DexString boxedNumberDescriptor = createString("Ljava/lang/Number;");

  public final DexString unboxBooleanMethodName = createString("booleanValue");
  public final DexString unboxByteMethodName = createString("byteValue");
  public final DexString unboxCharMethodName = createString("charValue");
  public final DexString unboxShortMethodName = createString("shortValue");
  public final DexString unboxIntMethodName = createString("intValue");
  public final DexString unboxLongMethodName = createString("longValue");
  public final DexString unboxFloatMethodName = createString("floatValue");
  public final DexString unboxDoubleMethodName = createString("doubleValue");

  public final DexString valueOfMethodName = createString("valueOf");

  public final DexString getClassMethodName = createString("getClass");
  public final DexString ordinalMethodName = createString("ordinal");
  public final DexString desiredAssertionStatusMethodName = createString("desiredAssertionStatus");
  public final DexString getNameName = createString("getName");
  public final DexString getSimpleNameName = createString("getSimpleName");
  public final DexString assertionsDisabled = createString("$assertionsDisabled");

  public final DexString stringDescriptor = createString("Ljava/lang/String;");
  public final DexString stringArrayDescriptor = createString("[Ljava/lang/String;");
  public final DexString objectDescriptor = createString("Ljava/lang/Object;");
  public final DexString objectArrayDescriptor = createString("[Ljava/lang/Object;");
  public final DexString classDescriptor = createString("Ljava/lang/Class;");
  public final DexString enumDescriptor = createString("Ljava/lang/Enum;");
  public final DexString annotationDescriptor = createString("Ljava/lang/annotation/Annotation;");
  public final DexString throwableDescriptor = createString("Ljava/lang/Throwable;");
  public final DexString objectsDescriptor = createString("Ljava/util/Objects;");
  public final DexString varHandleDescriptor = createString("Ljava/lang/invoke/VarHandle;");

  public final DexString constructorMethodName = createString(Constants.INSTANCE_INITIALIZER_NAME);
  public final DexString classConstructorMethodName = createString(Constants.CLASS_INITIALIZER_NAME);

  public final DexString thisName = createString("this");

  private final DexString charArrayDescriptor = createString("[C");
  private final DexType charArrayType = createType(charArrayDescriptor);
  public final DexString throwableArrayDescriptor = createString("[Ljava/lang/Throwable;");

  public final DexType booleanType = createType(booleanDescriptor);
  public final DexType byteType = createType(byteDescriptor);
  public final DexType charType = createType(charDescriptor);
  public final DexType doubleType = createType(doubleDescriptor);
  public final DexType floatType = createType(floatDescriptor);
  public final DexType intType = createType(intDescriptor);
  public final DexType longType = createType(longDescriptor);
  public final DexType shortType = createType(shortDescriptor);
  public final DexType voidType = createType(voidDescriptor);

  public final DexType boxedBooleanType = createType(boxedBooleanDescriptor);
  public final DexType boxedByteType = createType(boxedByteDescriptor);
  public final DexType boxedCharType = createType(boxedCharDescriptor);
  public final DexType boxedDoubleType = createType(boxedDoubleDescriptor);
  public final DexType boxedFloatType = createType(boxedFloatDescriptor);
  public final DexType boxedIntType = createType(boxedIntDescriptor);
  public final DexType boxedLongType = createType(boxedLongDescriptor);
  public final DexType boxedShortType = createType(boxedShortDescriptor);
  public final DexType boxedNumberType = createType(boxedNumberDescriptor);

  public final DexType stringType = createType(stringDescriptor);
  public final DexType stringArrayType = createType(stringArrayDescriptor);
  public final DexType objectType = createType(objectDescriptor);
  public final DexType objectArrayType = createType(objectArrayDescriptor);
  public final DexType enumType = createType(enumDescriptor);
  public final DexType annotationType = createType(annotationDescriptor);
  public final DexType throwableType = createType(throwableDescriptor);

  public final DexType stringBuilderType = createType("Ljava/lang/StringBuilder;");
  public final DexType stringBufferType = createType("Ljava/lang/StringBuffer;");

  public final DexType varHandleType = createType(varHandleDescriptor);

  public final StringBuildingMethods stringBuilderMethods = new StringBuildingMethods(stringBuilderType);
  public final StringBuildingMethods stringBufferMethods = new StringBuildingMethods(stringBufferType);
  public final ObjectsMethods objectsMethods = new ObjectsMethods();
  public final ObjectMethods objectMethods = new ObjectMethods();
  public final LongMethods longMethods = new LongMethods();
  public final ThrowableMethods throwableMethods = new ThrowableMethods();
  public final ClassMethods classMethods = new ClassMethods();

  // Dex system annotations.
  // See https://source.android.com/devices/tech/dalvik/dex-format.html#system-annotation
  public final DexType annotationDefault = createType("Ldalvik/annotation/AnnotationDefault;");
  public final DexType annotationEnclosingClass = createType("Ldalvik/annotation/EnclosingClass;");
  public final DexType annotationEnclosingMethod = createType(
      "Ldalvik/annotation/EnclosingMethod;");
  public final DexType annotationInnerClass = createType("Ldalvik/annotation/InnerClass;");
  public final DexType annotationMemberClasses = createType("Ldalvik/annotation/MemberClasses;");
  public final DexType annotationMethodParameters = createType(
      "Ldalvik/annotation/MethodParameters;");
  public final DexType annotationSignature = createType("Ldalvik/annotation/Signature;");
  public final DexType annotationSourceDebugExtension = createType(
      "Ldalvik/annotation/SourceDebugExtension;");
  public final DexType annotationThrows = createType("Ldalvik/annotation/Throws;");
  public final DexType annotationSynthesizedClassMap =
      createType("Lcom/android/tools/r8/annotations/SynthesizedClassMap;");

  public synchronized void clearSubtypeInformation() {
    types.values().forEach(DexType::clearSubtypeInformation);
  }

  public class LongMethods {

    public final DexMethod compare;

    private LongMethods() {
      compare = createMethod(boxedLongDescriptor,
          createString("compare"), intDescriptor, new DexString[]{longDescriptor, longDescriptor});
    }
  }

  public class ThrowableMethods {

    public final DexMethod addSuppressed;
    public final DexMethod getSuppressed;

    private ThrowableMethods() {
      addSuppressed = createMethod(throwableDescriptor,
          createString("addSuppressed"), voidDescriptor, new DexString[]{throwableDescriptor});
      getSuppressed = createMethod(throwableDescriptor,
          createString("getSuppressed"), throwableArrayDescriptor, DexString.EMPTY_ARRAY);
    }
  }

  public class ObjectMethods {

    public final DexMethod getClass;

    private ObjectMethods() {
      getClass = createMethod(objectsDescriptor, getClassMethodName, classDescriptor,
          DexString.EMPTY_ARRAY);
    }
  }

  public class ObjectsMethods {

    public DexMethod requireNonNull;

    private ObjectsMethods() {
      requireNonNull = createMethod(objectsDescriptor,
          createString("requireNonNull"), objectDescriptor, new DexString[]{objectDescriptor});
    }
  }

  public class ClassMethods {

    public DexMethod desiredAssertionStatus;
    public DexMethod getName;
    public DexMethod getSimpleName;

    private ClassMethods() {
      desiredAssertionStatus = createMethod(classDescriptor,
          desiredAssertionStatusMethodName, booleanDescriptor, DexString.EMPTY_ARRAY);
      getName = createMethod(classDescriptor, getNameName, stringDescriptor, DexString.EMPTY_ARRAY);
      getSimpleName = createMethod(classDescriptor,
          getSimpleNameName, stringDescriptor, DexString.EMPTY_ARRAY);
    }
  }


  public class StringBuildingMethods {

    public final DexMethod appendBoolean;
    public final DexMethod appendChar;
    public final DexMethod appendCharArray;
    public final DexMethod appendSubCharArray;
    public final DexMethod appendCharSequence;
    public final DexMethod appendSubCharSequence;
    public final DexMethod appendInt;
    public final DexMethod appendDouble;
    public final DexMethod appendFloat;
    public final DexMethod appendLong;
    public final DexMethod appendObject;
    public final DexMethod appendString;
    public final DexMethod appendStringBuffer;

    private StringBuildingMethods(DexType receiver) {
      DexType sbufType = createType(createString("Ljava/lang/StringBuffer;"));
      DexType charSequenceType = createType(createString("Ljava/lang/CharSequence;"));
      DexString append = createString("append");
      DexString toStringMethodName = createString("toString");

      appendBoolean = createMethod(receiver, createProto(receiver, booleanType), append);
      appendChar = createMethod(receiver, createProto(receiver, charType), append);
      appendCharArray = createMethod(receiver, createProto(receiver, charArrayType), append);
      appendSubCharArray =
          createMethod(receiver, createProto(receiver, charArrayType, intType, intType), append);
      appendCharSequence = createMethod(receiver, createProto(receiver, charSequenceType), append);
      appendSubCharSequence =
          createMethod(receiver, createProto(receiver, charSequenceType, intType, intType), append);
      appendInt = createMethod(receiver, createProto(receiver, intType), append);
      appendDouble = createMethod(receiver, createProto(receiver, doubleType), append);
      appendFloat = createMethod(receiver, createProto(receiver, floatType), append);
      appendLong = createMethod(receiver, createProto(receiver, longType), append);
      appendObject = createMethod(receiver, createProto(receiver, objectType), append);
      appendString = createMethod(receiver, createProto(receiver, stringType), append);
      appendStringBuffer = createMethod(receiver, createProto(receiver, sbufType), append);
    }

    public void forEachAppendMethod(Consumer<DexMethod> consumer) {
      consumer.accept(appendBoolean);
      consumer.accept(appendChar);
      consumer.accept(appendCharArray);
      consumer.accept(appendSubCharArray);
      consumer.accept(appendCharSequence);
      consumer.accept(appendSubCharSequence);
      consumer.accept(appendInt);
      consumer.accept(appendDouble);
      consumer.accept(appendFloat);
      consumer.accept(appendLong);
      consumer.accept(appendObject);
      consumer.accept(appendString);
      consumer.accept(appendStringBuffer);
      consumer.accept(appendBoolean);
    }
  }

  private static <T extends DexItem> T canonicalize(ConcurrentHashMap<T, T> map, T item) {
    assert item != null;
    assert !internalSentinels.contains(item);
    T previous = map.putIfAbsent(item, item);
    return previous == null ? item : previous;
  }

  public DexString createString(int size, byte[] content) {
    assert !sorted;
    return canonicalize(strings, new DexString(size, content));
  }

  public DexString createString(String source) {
    assert !sorted;
    return canonicalize(strings, new DexString(source));
  }

  // Debugging support to extract marking string.
  synchronized public Marker extractMarker() {
    // This is slow but it is not needed for any production code yet.
    for (DexString dexString : strings.keySet()) {
      Marker result = Marker.parse(dexString.toString());
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  synchronized public DexType createType(DexString descriptor) {
    assert !sorted;
    assert descriptor != null;
    DexType result = types.get(descriptor);
    if (result == null) {
      result = new DexType(descriptor);
      assert result.isArrayType() || result.isClassType() || result.isPrimitiveType() ||
          result.isVoidType();
      assert !internalSentinels.contains(result);
      types.put(descriptor, result);
    }
    return result;
  }

  public DexType createType(String descriptor) {
    return createType(createString(descriptor));
  }

  public DexField createField(DexType clazz, DexType type, DexString name) {
    assert !sorted;
    DexField field = new DexField(clazz, type, name);
    return canonicalize(fields, field);
  }

  public DexField createField(DexType clazz, DexType type, String name) {
    return createField(clazz, type, createString(name));
  }

  public DexProto createProto(DexType returnType, DexString shorty, DexTypeList parameters) {
    assert !sorted;
    DexProto proto = new DexProto(shorty, returnType, parameters);
    return canonicalize(protos, proto);
  }

  public DexProto createProto(DexType returnType, DexType... parameters) {
    assert !sorted;
    return createProto(returnType, createShorty(returnType, parameters),
        parameters.length == 0 ? DexTypeList.empty() : new DexTypeList(parameters));
  }

  private DexString createShorty(DexType returnType, DexType[] argumentTypes) {
    StringBuilder shortyBuilder = new StringBuilder();
    shortyBuilder.append(returnType.toShorty());
    for (DexType argumentType : argumentTypes) {
      shortyBuilder.append(argumentType.toShorty());
    }
    return createString(shortyBuilder.toString());
  }

  public DexMethod createMethod(DexType holder, DexProto proto, DexString name) {
    assert !sorted;
    DexMethod method = new DexMethod(holder, proto, name);
    return canonicalize(methods, method);
  }

  public DexMethod createMethod(DexType holder, DexProto proto, String name) {
    return createMethod(holder, proto, createString(name));
  }

  public DexMethodHandle createMethodHandle(
      MethodHandleType type,
      Descriptor<? extends DexItem, ? extends Descriptor<?, ?>> fieldOrMethod) {
    assert !sorted;
    DexMethodHandle methodHandle = new DexMethodHandle(type, fieldOrMethod);
    return canonicalize(methodHandles, methodHandle);
  }

  public DexCallSite createCallSite(
      DexString methodName, DexProto methodProto,
      DexMethodHandle bootstrapMethod, List<DexValue> bootstrapArgs) {
    assert !sorted;
    DexCallSite callSite = new DexCallSite(methodName, methodProto, bootstrapMethod, bootstrapArgs);
    return canonicalize(callSites, callSite);
  }

  public DexMethod createMethod(DexString clazzDescriptor, DexString name,
      DexString returnTypeDescriptor,
      DexString[] parameterDescriptors) {
    assert !sorted;
    DexType clazz = createType(clazzDescriptor);
    DexType returnType = createType(returnTypeDescriptor);
    DexType[] parameterTypes = new DexType[parameterDescriptors.length];
    for (int i = 0; i < parameterDescriptors.length; i++) {
      parameterTypes[i] = createType(parameterDescriptors[i]);
    }
    DexProto proto = createProto(returnType, parameterTypes);

    return createMethod(clazz, proto, name);
  }

  public AdvanceLine createAdvanceLine(int delta) {
    synchronized (advanceLines) {
      return advanceLines.computeIfAbsent(delta, AdvanceLine::new);
    }
  }

  public AdvancePC createAdvancePC(int delta) {
    synchronized (advancePCs) {
      return advancePCs.computeIfAbsent(delta, AdvancePC::new);
    }
  }

  public Default createDefault(int value) {
    synchronized (defaults) {
      return defaults.computeIfAbsent(value, Default::new);
    }
  }

  public EndLocal createEndLocal(int registerNum) {
    synchronized (endLocals) {
      return endLocals.computeIfAbsent(registerNum, EndLocal::new);
    }
  }

  public RestartLocal createRestartLocal(int registerNum) {
    synchronized (restartLocals) {
      return restartLocals.computeIfAbsent(registerNum, RestartLocal::new);
    }
  }

  public SetEpilogueBegin createSetEpilogueBegin() {
    return setEpilogueBegin;
  }

  public SetPrologueEnd createSetPrologueEnd() {
    return setPrologueEnd;
  }

  public SetFile createSetFile(DexString fileName) {
    synchronized (setFiles) {
      return setFiles.computeIfAbsent(fileName, SetFile::new);
    }
  }

  public boolean isConstructor(DexMethod method) {
    return method.name == constructorMethodName;
  }

  public boolean isClassConstructor(DexMethod method) {
    return method.name == classConstructorMethodName;
  }

  private static <S extends PresortedComparable<S>> void assignSortedIndices(Collection<S> items,
      NamingLens namingLens) {
    List<S> sorted = new ArrayList<>(items);
    sorted.sort((a, b) -> a.layeredCompareTo(b, namingLens));
    int i = 0;
    for (S value : sorted) {
      value.setSortedIndex(i++);
    }
  }

  synchronized public void sort(NamingLens namingLens) {
    assert !sorted;
    assignSortedIndices(strings.values(), namingLens);
    assignSortedIndices(types.values(), namingLens);
    assignSortedIndices(fields.values(), namingLens);
    assignSortedIndices(protos.values(), namingLens);
    assignSortedIndices(methods.values(), namingLens);
    sorted = true;
  }

  synchronized public void resetSortedIndices() {
    if (!sorted) {
      return;
    }
    // Only used for asserting that we don't use the sorted index after we build the graph.
    strings.values().forEach(IndexedDexItem::resetSortedIndex);
    types.values().forEach(IndexedDexItem::resetSortedIndex);
    fields.values().forEach(IndexedDexItem::resetSortedIndex);
    protos.values().forEach(IndexedDexItem::resetSortedIndex);
    methods.values().forEach(IndexedDexItem::resetSortedIndex);
    sorted = false;
  }

  synchronized public void forAllTypes(Consumer<DexType> f) {
    new ArrayList<>(types.values()).forEach(f);
  }
}
