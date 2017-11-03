// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import static com.android.tools.r8.utils.EncodedValueUtils.parseDouble;
import static com.android.tools.r8.utils.EncodedValueUtils.parseFloat;
import static com.android.tools.r8.utils.EncodedValueUtils.parseSigned;
import static com.android.tools.r8.utils.EncodedValueUtils.parseUnsigned;

import com.android.tools.r8.Resource.Origin;
import com.android.tools.r8.Resource.PathOrigin;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.code.InstructionFactory;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.ClassKind;
import com.android.tools.r8.graph.Descriptor;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationElement;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexAnnotationSetRefList;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexCode;
import com.android.tools.r8.graph.DexCode.Try;
import com.android.tools.r8.graph.DexCode.TryHandler;
import com.android.tools.r8.graph.DexCode.TryHandler.TypeAddrPair;
import com.android.tools.r8.graph.DexDebugEvent;
import com.android.tools.r8.graph.DexDebugInfo;
import com.android.tools.r8.graph.DexEncodedAnnotation;
import com.android.tools.r8.graph.DexEncodedArray;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMemberAnnotation;
import com.android.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation;
import com.android.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation;
import com.android.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexMethodHandle.MethodHandleType;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.android.tools.r8.graph.DexValue.DexValueMethodType;
import com.android.tools.r8.graph.DexValue.DexValueNull;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.graph.FieldAccessFlags;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.utils.ProgramResource.Kind;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class DexFileReader {

  final int NO_INDEX = -1;
  private final Origin origin;
  private DexFile file;
  private final Segment[] segments;
  private int[] stringIDs;
  private final ClassKind classKind;

  public static Segment[] parseMapFrom(Path file) throws IOException {
    return parseMapFrom(new DexFile(file.toString()), new PathOrigin(file, Origin.root()));
  }

  public static Segment[] parseMapFrom(InputStream stream, Origin origin) throws IOException {
    return parseMapFrom(new DexFile(stream), origin);
  }

  private static Segment[] parseMapFrom(DexFile dex, Origin origin) {
    DexFileReader reader = new DexFileReader(origin, dex, ClassKind.PROGRAM, new DexItemFactory());
    return reader.segments;
  }

  public void close() {
    // This close behavior is needed to reduce peak memory usage of D8/R8.
    indexedItems = null;
    codes = null;
    offsetMap = null;
    file = null;
    stringIDs = null;
  }

  // Mapping from indexes to indexable dex items.
  private OffsetToObjectMapping indexedItems = new OffsetToObjectMapping();

  // Mapping from offset to code item;
  private Int2ObjectMap<DexCode> codes = new Int2ObjectOpenHashMap<>();

  // Mapping from offset to dex item;
  private Int2ObjectMap<Object> offsetMap = new Int2ObjectOpenHashMap<>();

  // Factory to canonicalize certain dexitems.
  private final DexItemFactory dexItemFactory;

  public DexFileReader(
      Origin origin, DexFile file, ClassKind classKind, DexItemFactory dexItemFactory) {
    this.origin = origin;
    this.file = file;
    this.dexItemFactory = dexItemFactory;
    file.setByteOrder();
    segments = parseMap();
    parseStringIDs();
    this.classKind = classKind;
  }

  public OffsetToObjectMapping getIndexedItemsMap() {
    return indexedItems;
  }

  void addCodeItemsTo() {
    if (classKind == ClassKind.LIBRARY) {
      // Ignore contents of library files.
      return;
    }
    Segment segment = lookupSegment(Constants.TYPE_CODE_ITEM);
    if (segment.length == 0) {
      return;
    }
    file.position(segment.offset);
    for (int i = 0; i < segment.length; i++) {
      file.align(4);  // code items are 4 byte aligned.
      int offset = file.position();
      DexCode code = parseCodeItem();
      codes.put(offset, code);  // Update the file local offset to code mapping.
    }
  }

  private DexTypeList parseTypeList() {
    DexType[] result = new DexType[file.getUint()];
    for (int j = 0; j < result.length; j++) {
      result[j] = indexedItems.getType(file.getUshort());
    }
    return new DexTypeList(result);
  }

  private DexTypeList typeListAt(int offset) {
    if (offset == 0) {
      return DexTypeList.empty();
    }
    return (DexTypeList) cacheAt(offset, this::parseTypeList);
  }

  public DexValue parseEncodedValue() {
    int header = file.get() & 0xff;
    int valueArg = header >> 5;
    int valueType = header & 0x1f;
    switch (valueType) {
      case DexValue.VALUE_BYTE: {
        assert valueArg == 0;
        byte value = (byte) parseSigned(file, 1);
        return DexValue.DexValueByte.create(value);
      }
      case DexValue.VALUE_SHORT: {
        int size = valueArg + 1;
        short value = (short) parseSigned(file, size);
        return DexValue.DexValueShort.create(value);
      }
      case DexValue.VALUE_CHAR: {
        int size = valueArg + 1;
        char value = (char) parseUnsigned(file, size);
        return DexValue.DexValueChar.create(value);
      }
      case DexValue.VALUE_INT: {
        int size = valueArg + 1;
        int value = (int) parseSigned(file, size);
        return DexValue.DexValueInt.create(value);
      }
      case DexValue.VALUE_LONG: {
        int size = valueArg + 1;
        long value = parseSigned(file, size);
        return DexValue.DexValueLong.create(value);
      }
      case DexValue.VALUE_FLOAT: {
        int size = valueArg + 1;
        return DexValue.DexValueFloat.create(parseFloat(file, size));
      }
      case DexValue.VALUE_DOUBLE: {
        int size = valueArg + 1;
        return DexValue.DexValueDouble.create(parseDouble(file, size));
      }
      case DexValue.VALUE_STRING: {
        int size = valueArg + 1;
        int index = (int) parseUnsigned(file, size);
        DexString value = indexedItems.getString(index);
        return new DexValue.DexValueString(value);
      }
      case DexValue.VALUE_TYPE: {
        int size = valueArg + 1;
        DexType value = indexedItems.getType((int) parseUnsigned(file, size));
        return new DexValue.DexValueType(value);
      }
      case DexValue.VALUE_FIELD: {
        int size = valueArg + 1;
        DexField value = indexedItems.getField((int) parseUnsigned(file, size));
        return new DexValue.DexValueField(value);
      }
      case DexValue.VALUE_METHOD: {
        int size = valueArg + 1;
        DexMethod value = indexedItems.getMethod((int) parseUnsigned(file, size));
        return new DexValue.DexValueMethod(value);
      }
      case DexValue.VALUE_ENUM: {
        int size = valueArg + 1;
        DexField value = indexedItems.getField((int) parseUnsigned(file, size));
        return new DexValue.DexValueEnum(value);
      }
      case DexValue.VALUE_ARRAY: {
        assert valueArg == 0;
        return new DexValue.DexValueArray(parseEncodedArrayValues());
      }
      case DexValue.VALUE_ANNOTATION: {
        assert valueArg == 0;
        return new DexValue.DexValueAnnotation(parseEncodedAnnotation());
      }
      case DexValue.VALUE_NULL: {
        assert valueArg == 0;
        return DexValueNull.NULL;
      }
      case DexValue.VALUE_BOOLEAN: {
        // 0 is false, and 1 is true.
        return DexValue.DexValueBoolean.create(valueArg != 0);
      }
      case DexValue.VALUE_METHOD_TYPE: {
        int size = valueArg + 1;
        DexProto value = indexedItems.getProto((int) parseUnsigned(file, size));
        return new DexValue.DexValueMethodType(value);
      }
      case DexValue.VALUE_METHOD_HANDLE: {
        int size = valueArg + 1;
        DexMethodHandle value = indexedItems.getMethodHandle((int) parseUnsigned(file, size));
        return new DexValue.DexValueMethodHandle(value);
      }
      default:
        throw new IndexOutOfBoundsException();
    }
  }

  private DexEncodedAnnotation parseEncodedAnnotation() {
    int typeIdx = file.getUleb128();
    int size = file.getUleb128();
    DexAnnotationElement[] elements = new DexAnnotationElement[size];
    for (int i = 0; i < size; i++) {
      int nameIdx = file.getUleb128();
      DexValue value = parseEncodedValue();
      elements[i] = new DexAnnotationElement(indexedItems.getString(nameIdx), value);
    }
    return new DexEncodedAnnotation(indexedItems.getType(typeIdx), elements);
  }

  private DexValue[] parseEncodedArrayValues() {
    int size = file.getUleb128();
    DexValue[] values = new DexValue[size];
    for (int i = 0; i < size; i++) {
      values[i] = parseEncodedValue();
    }
    return values;
  }


  private DexEncodedArray parseEncodedArray() {
    return new DexEncodedArray(parseEncodedArrayValues());
  }

  private DexEncodedArray encodedArrayAt(int offset) {
    return (DexEncodedArray) cacheAt(offset, this::parseEncodedArray);
  }

  private DexFieldAnnotation[] parseFieldAnnotations(int size) {
    if (size == 0) {
      return null;
    }
    int[] fieldIndices = new int[size];
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      fieldIndices[i] = file.getUint();
      annotationOffsets[i] = file.getUint();
    }
    int saved = file.position();
    DexFieldAnnotation[] result = new DexFieldAnnotation[size];
    for (int i = 0; i < size; i++) {
      DexField field = indexedItems.getField(fieldIndices[i]);
      DexAnnotationSet annotation = annotationSetAt(annotationOffsets[i]);
      result[i] = new DexFieldAnnotation(field, annotation);
    }
    file.position(saved);
    return result;
  }

  private DexMethodAnnotation[] parseMethodAnnotations(int size) {
    if (size == 0) {
      return null;
    }
    int[] methodIndices = new int[size];
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      methodIndices[i] = file.getUint();
      annotationOffsets[i] = file.getUint();
    }
    int saved = file.position();
    DexMethodAnnotation[] result = new DexMethodAnnotation[size];
    for (int i = 0; i < size; i++) {
      DexMethod method = indexedItems.getMethod(methodIndices[i]);
      DexAnnotationSet annotation = annotationSetAt(annotationOffsets[i]);
      result[i] = new DexMethodAnnotation(method, annotation);
    }
    file.position(saved);
    return result;
  }

  private DexAnnotationSetRefList annotationSetRefListAt(int offset) {
    return (DexAnnotationSetRefList) cacheAt(offset, this::parseAnnotationSetRefList);
  }

  private DexAnnotationSetRefList parseAnnotationSetRefList() {
    int size = file.getUint();
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      annotationOffsets[i] = file.getUint();
    }
    DexAnnotationSet[] values = new DexAnnotationSet[size];
    for (int i = 0; i < size; i++) {
      values[i] = annotationSetAt(annotationOffsets[i]);
    }
    return new DexAnnotationSetRefList(values);
  }

  private DexParameterAnnotation[] parseParameterAnnotations(int size) {
    if (size == 0) {
      return null;
    }
    int[] methodIndices = new int[size];
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      methodIndices[i] = file.getUint();
      annotationOffsets[i] = file.getUint();
    }
    int saved = file.position();
    DexParameterAnnotation[] result = new DexParameterAnnotation[size];
    for (int i = 0; i < size; i++) {
      DexMethod method = indexedItems.getMethod(methodIndices[i]);
      result[i] = new DexParameterAnnotation(
          method,
          annotationSetRefListAt(annotationOffsets[i]));
    }
    file.position(saved);
    return result;
  }

  private <T> Object cacheAt(int offset, Supplier<T> function, Supplier<T> defaultValue) {
    if (offset == 0) {
      return defaultValue.get();
    }
    return cacheAt(offset, function);
  }

  private <T> Object cacheAt(int offset, Supplier<T> function) {
    if (offset == 0) {
      return null;  // return null for offset zero.
    }
    Object result = offsetMap.get(offset);
    if (result != null) {
      return result;  // return the cached result.
    }
    // Cache is empty so parse the structure.
    file.position(offset);
    result = function.get();
    // Update the map.
    offsetMap.put(offset, result);
    assert offsetMap.get(offset) == result;
    return result;
  }

  private DexAnnotation parseAnnotation() {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Reading Annotation @ 0x%08x.", file.position());
    }
    int visibility = file.get();
    return new DexAnnotation(visibility, parseEncodedAnnotation());
  }

  private DexAnnotation annotationAt(int offset) {
    return (DexAnnotation) cacheAt(offset, this::parseAnnotation);
  }

  private DexAnnotationSet parseAnnotationSet() {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Reading AnnotationSet @ 0x%08x.", file.position());
    }
    int size = file.getUint();
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      annotationOffsets[i] = file.getUint();
    }
    DexAnnotation[] result = new DexAnnotation[size];
    for (int i = 0; i < size; i++) {
      result[i] = annotationAt(annotationOffsets[i]);
    }
    return new DexAnnotationSet(result);
  }

  private DexAnnotationSet annotationSetAt(int offset) {
    return (DexAnnotationSet) cacheAt(offset, this::parseAnnotationSet, DexAnnotationSet::empty);
  }

  private AnnotationsDirectory annotationsDirectoryAt(int offset) {
    return (AnnotationsDirectory) cacheAt(offset, this::parseAnnotationsDirectory,
        AnnotationsDirectory::empty);
  }

  private AnnotationsDirectory parseAnnotationsDirectory() {
    int classAnnotationsOff = file.getUint();
    int fieldsSize = file.getUint();
    int methodsSize = file.getUint();
    int parametersSize = file.getUint();
    final DexFieldAnnotation[] fields = parseFieldAnnotations(fieldsSize);
    final DexMethodAnnotation[] methods = parseMethodAnnotations(methodsSize);
    final DexParameterAnnotation[] parameters = parseParameterAnnotations(parametersSize);
    return new AnnotationsDirectory(
        annotationSetAt(classAnnotationsOff),
        fields,
        methods,
        parameters);
  }

  private DexDebugInfo debugInfoAt(int offset) {
    return (DexDebugInfo) cacheAt(offset, this::parseDebugInfo);
  }

  private DexDebugInfo parseDebugInfo() {
    int start = file.getUleb128();
    int parametersSize = file.getUleb128();
    DexString[] parameters = new DexString[parametersSize];
    for (int i = 0; i < parametersSize; i++) {
      int index = file.getUleb128p1();
      if (index != NO_INDEX) {
        parameters[i] = indexedItems.getString(index);
      }
    }
    List<DexDebugEvent> events = new ArrayList<>();
    for (int head = file.getUbyte(); head != Constants.DBG_END_SEQUENCE; head = file.getUbyte()) {
      switch (head) {
        case Constants.DBG_ADVANCE_PC:
          events.add(dexItemFactory.createAdvancePC(file.getUleb128()));
          break;
        case Constants.DBG_ADVANCE_LINE:
          events.add(dexItemFactory.createAdvanceLine(file.getSleb128()));
          break;
        case Constants.DBG_START_LOCAL: {
          int registerNum = file.getUleb128();
          int nameIdx = file.getUleb128p1();
          int typeIdx = file.getUleb128p1();
          events.add(new DexDebugEvent.StartLocal(
              registerNum,
              nameIdx == NO_INDEX ? null : indexedItems.getString(nameIdx),
              typeIdx == NO_INDEX ? null : indexedItems.getType(typeIdx),
              null));
          break;
        }
        case Constants.DBG_START_LOCAL_EXTENDED: {
          int registerNum = file.getUleb128();
          int nameIdx = file.getUleb128p1();
          int typeIdx = file.getUleb128p1();
          int sigIdx = file.getUleb128p1();
          events.add(new DexDebugEvent.StartLocal(
              registerNum,
              nameIdx == NO_INDEX ? null : indexedItems.getString(nameIdx),
              typeIdx == NO_INDEX ? null : indexedItems.getType(typeIdx),
              sigIdx == NO_INDEX ? null : indexedItems.getString(sigIdx)));
          break;
        }
        case Constants.DBG_END_LOCAL: {
          events.add(dexItemFactory.createEndLocal(file.getUleb128()));
          break;
        }
        case Constants.DBG_RESTART_LOCAL: {
          events.add(dexItemFactory.createRestartLocal(file.getUleb128()));
          break;
        }
        case Constants.DBG_SET_PROLOGUE_END: {
          events.add(dexItemFactory.createSetPrologueEnd());
          break;
        }
        case Constants.DBG_SET_EPILOGUE_BEGIN: {
          events.add(dexItemFactory.createSetEpilogueBegin());
          break;
        }
        case Constants.DBG_SET_FILE: {
          int nameIdx = file.getUleb128p1();
          DexString sourceFile = nameIdx == NO_INDEX ? null : indexedItems.getString(nameIdx);
          events.add(dexItemFactory.createSetFile(sourceFile));
          break;
        }
        default: {
          assert head >= 0x0a && head <= 0xff;
          events.add(dexItemFactory.createDefault(head));
        }
      }
    }
    return new DexDebugInfo(start, parameters, events.toArray(new DexDebugEvent[events.size()]));
  }

  private static class MemberAnnotationIterator<S extends Descriptor<?, S>, T extends DexItem> {

    private int index = 0;
    private final DexMemberAnnotation<S, T>[] annotations;
    private final Supplier<T> emptyValue;

    private MemberAnnotationIterator(DexMemberAnnotation<S, T>[] annotations,
        Supplier<T> emptyValue) {
      this.annotations = annotations;
      this.emptyValue = emptyValue;
    }

    // Get the annotation set for an item. This method assumes that it is always called with
    // an item that is higher in the sorting order than the last item.
    T getNextFor(S item) {
      // TODO(ager): We could use the indices from the file to make this search faster using
      // compareTo instead of slowCompareTo. That would require us to assign indices during
      // reading. Those indices should be cleared after reading to make sure that we resort
      // everything correctly at the end.
      while (index < annotations.length && annotations[index].item.slowCompareTo(item) < 0) {
        index++;
      }
      if (index >= annotations.length || !annotations[index].item.equals(item)) {
        return emptyValue.get();
      }
      return annotations[index].annotations;
    }
  }

  private DexEncodedField[] readFields(int size, DexFieldAnnotation[] annotations,
      DexValue[] staticValues) {
    DexEncodedField[] fields = new DexEncodedField[size];
    int fieldIndex = 0;
    MemberAnnotationIterator<DexField, DexAnnotationSet> annotationIterator =
        new MemberAnnotationIterator<>(annotations, DexAnnotationSet::empty);
    for (int i = 0; i < size; i++) {
      fieldIndex += file.getUleb128();
      DexField field = indexedItems.getField(fieldIndex);
      FieldAccessFlags accessFlags = FieldAccessFlags.fromDexAccessFlags(file.getUleb128());
      DexAnnotationSet fieldAnnotations = annotationIterator.getNextFor(field);
      DexValue staticValue = null;
      if (accessFlags.isStatic()) {
        if (staticValues != null && i < staticValues.length) {
          staticValue = staticValues[i];
        } else {
          staticValue = DexValue.defaultForType(field.type, dexItemFactory);
        }
      }
      fields[i] = new DexEncodedField(field, accessFlags, fieldAnnotations, staticValue);
    }
    return fields;
  }

  private DexEncodedMethod[] readMethods(int size, DexMethodAnnotation[] annotations,
      DexParameterAnnotation[] parameters, boolean skipCodes) {
    DexEncodedMethod[] methods = new DexEncodedMethod[size];
    int methodIndex = 0;
    MemberAnnotationIterator<DexMethod, DexAnnotationSet> annotationIterator =
        new MemberAnnotationIterator<>(annotations, DexAnnotationSet::empty);
    MemberAnnotationIterator<DexMethod, DexAnnotationSetRefList> parameterAnnotationsIterator =
        new MemberAnnotationIterator<>(parameters, DexAnnotationSetRefList::empty);
    for (int i = 0; i < size; i++) {
      methodIndex += file.getUleb128();
      MethodAccessFlags accessFlags = MethodAccessFlags.fromDexAccessFlags(file.getUleb128());
      int codeOff = file.getUleb128();
      DexCode code = null;
      if (!skipCodes) {
        assert codeOff == 0 || codes.get(codeOff) != null;
        code = codes.get(codeOff);
      }
      DexMethod method = indexedItems.getMethod(methodIndex);
      methods[i] = new DexEncodedMethod(method, accessFlags, annotationIterator.getNextFor(method),
          parameterAnnotationsIterator.getNextFor(method), code);
    }
    return methods;
  }

  void addClassDefsTo(Consumer<DexClass> classCollection) {
    final Segment segment = lookupSegment(Constants.TYPE_CLASS_DEF_ITEM);
    final int length = segment.length;
    indexedItems.initializeClasses(length);
    if (length == 0) {
      return;
    }
    file.position(segment.offset);

    int[] classIndices = new int[length];
    int[] accessFlags = new int[length];
    int[] superclassIndices = new int[length];
    int[] interfacesOffsets = new int[length];
    int[] sourceFileIndices = new int[length];
    int[] annotationsOffsets = new int[length];
    int[] classDataOffsets = new int[length];
    int[] staticValuesOffsets = new int[length];

    for (int i = 0; i < length; i++) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Reading ClassDef @ 0x%08x.", file.position());
      }
      classIndices[i] = file.getUint();
      accessFlags[i] = file.getUint();
      superclassIndices[i] = file.getInt();
      interfacesOffsets[i] = file.getUint();
      sourceFileIndices[i] = file.getInt();
      annotationsOffsets[i] = file.getUint();
      classDataOffsets[i] = file.getUint();
      staticValuesOffsets[i] = file.getUint();
    }

    for (int i = 0; i < length; i++) {
      int superclassIdx = superclassIndices[i];
      DexType superclass = superclassIdx == NO_INDEX ? null : indexedItems.getType(superclassIdx);
      int srcIdx = sourceFileIndices[i];
      DexString source = srcIdx == NO_INDEX ? null : indexedItems.getString(srcIdx);
      // fix annotations.
      DexType type = indexedItems.getType(classIndices[i]);
      ClassAccessFlags flags = ClassAccessFlags.fromDexAccessFlags(accessFlags[i]);
      DexClass clazz;
      DexEncodedField[] staticFields = DexEncodedField.EMPTY_ARRAY;
      DexEncodedField[] instanceFields = DexEncodedField.EMPTY_ARRAY;
      DexEncodedMethod[] directMethods = DexEncodedMethod.EMPTY_ARRAY;
      DexEncodedMethod[] virtualMethods = DexEncodedMethod.EMPTY_ARRAY;
      AnnotationsDirectory annotationsDirectory = annotationsDirectoryAt(annotationsOffsets[i]);
      if (classDataOffsets[i] != 0) {
        DexEncodedArray staticValues = encodedArrayAt(staticValuesOffsets[i]);

        file.position(classDataOffsets[i]);
        int staticFieldsSize = file.getUleb128();
        int instanceFieldsSize = file.getUleb128();
        int directMethodsSize = file.getUleb128();
        int virtualMethodsSize = file.getUleb128();

        staticFields = readFields(staticFieldsSize, annotationsDirectory.fields,
            staticValues != null ? staticValues.values : null);
        instanceFields = readFields(instanceFieldsSize, annotationsDirectory.fields, null);
        directMethods =
            readMethods(
                directMethodsSize,
                annotationsDirectory.methods,
                annotationsDirectory.parameters,
                classKind != ClassKind.PROGRAM);
        virtualMethods =
            readMethods(
                virtualMethodsSize,
                annotationsDirectory.methods,
                annotationsDirectory.parameters,
                classKind != ClassKind.PROGRAM);
      }
      clazz = classKind.create(
          type,
          Kind.DEX,
          origin,
          flags,
          superclass,
          typeListAt(interfacesOffsets[i]),
          source,
          annotationsDirectory.clazz,
          staticFields,
          instanceFields,
          directMethods,
          virtualMethods);
      classCollection.accept(clazz);  // Update the application object.
    }
  }

  private void parseStringIDs() {
    Segment segment = lookupSegment(Constants.TYPE_STRING_ID_ITEM);
    stringIDs = new int[segment.length];
    if (segment.length == 0) {
      return;
    }
    file.position(segment.offset);
    for (int i = 0; i < segment.length; i++) {
      stringIDs[i] = file.getUint();
    }
  }

  private Segment lookupSegment(int type) {
    for (Segment s : segments) {
      if (s.type == type) {
        return s;
      }
    }
    // If the segment doesn't exist, return an empty segment of this type.
    return new Segment(type, 0, 0, 0);
  }

  private Segment[] parseMap() {
    // Read the segments information from the MAP.
    int mapOffset = file.getUint(Constants.MAP_OFF_OFFSET);
    file.position(mapOffset);
    int mapSize = file.getUint();
    final Segment[] result = new Segment[mapSize];
    for (int i = 0; i < mapSize; i++) {
      int type = file.getUshort();
      int unused = file.getUshort();
      int size = file.getUint();
      int offset = file.getUint();
      result[i] = new Segment(type, unused, size, offset);
    }
    if (Log.ENABLED) {
      for (int i = 0; i < result.length; i++) {
        Segment segment = result[i];
        int nextOffset = i < result.length - 1 ? result[i + 1].offset : segment.offset;
        Log.debug(this.getClass(), "Read segment 0x%04x @ 0x%08x #items %08d size 0x%08x.",
            segment.type, segment.offset, segment.length, nextOffset - segment.offset);
      }
    }
    for (int i = 0; i < mapSize - 1; i++) {
      result[i].setEnd(result[i + 1].offset);
    }
    result[mapSize - 1].setEnd(file.end());
    return result;
  }

  private DexCode parseCodeItem() {
    int registerSize = file.getUshort();
    int insSize = file.getUshort();
    int outsSize = file.getUshort();
    int triesSize = file.getUshort();
    int debugInfoOff = file.getUint();
    int insnsSize = file.getUint();
    short[] code = new short[insnsSize];
    Try[] tries = new Try[triesSize];
    DexCode.TryHandler[] handlers = null;

    if (insnsSize != 0) {
      for (int i = 0; i < insnsSize; i++) {
        code[i] = file.getShort();
      }
      if (insnsSize % 2 != 0) {
        file.getUshort();  // Skip padding ushort
      }
      if (triesSize > 0) {
        Int2IntArrayMap handlerMap = new Int2IntArrayMap();
        // tries: try_item[tries_size].
        for (int i = 0; i < triesSize; i++) {
          int startAddr = file.getUint();
          int insnCount = file.getUshort();
          int handlerOff = file.getUshort();
          tries[i] = new Try(startAddr, insnCount, handlerOff);
        }
        // handlers: encoded_catch_handler_list
        int encodedCatchHandlerListPosition = file.position();
        // - size: uleb128
        int size = file.getUleb128();
        handlers = new TryHandler[size];
        // - list: encoded_catch_handler[handlers_size]
        for (int i = 0; i < size; i++) {
          // encoded_catch_handler
          int encodedCatchHandlerOffset = file.position() - encodedCatchHandlerListPosition;
          handlerMap.put(encodedCatchHandlerOffset, i);
          // - size:	sleb128
          int hsize = file.getSleb128();
          int realHsize = Math.abs(hsize);
          // - handlers	encoded_type_addr_pair[abs(size)]
          TryHandler.TypeAddrPair pairs[] = new TryHandler.TypeAddrPair[realHsize];
          for (int j = 0; j < realHsize; j++) {
            int typeIdx = file.getUleb128();
            int addr = file.getUleb128();
            pairs[j] = new TypeAddrPair(indexedItems.getType(typeIdx), addr);
          }
          int catchAllAddr = -1;
          if (hsize <= 0) {
            catchAllAddr = file.getUleb128();
          }
          handlers[i] = new TryHandler(pairs, catchAllAddr);
        }
        // Convert the handler offsets inside the Try objects to indexes.
        for (Try t : tries) {
          t.setHandlerIndex(handlerMap);
        }
      }
    }
    // Store and restore offset information around reading debug info.
    int saved = file.position();
    DexDebugInfo debugInfo = debugInfoAt(debugInfoOff);
    file.position(saved);
    InstructionFactory factory = new InstructionFactory();
    Instruction[] instructions =
        factory.readSequenceFrom(ShortBuffer.wrap(code), 0, code.length, indexedItems);
    return new DexCode(
        registerSize,
        insSize,
        outsSize,
        instructions,
        tries,
        handlers,
        debugInfo,
        factory.getHighestSortingString());
  }

  static void populateIndexTables(DexFileReader fileReader) {
    // Populate structures that are already sorted upon read.
    DexFileReader.populateStrings(fileReader);  // Depends on nothing.
    DexFileReader.populateTypes(fileReader);  // Depends on Strings.
    DexFileReader.populateFields(fileReader);  // Depends on Types, and Strings.
    DexFileReader.populateProtos(fileReader);  // Depends on Types and Strings.
    DexFileReader.populateMethods(fileReader);  // Depends on Protos, Types, and Strings.
    DexFileReader.populateMethodHandles(fileReader); // Depends on Methods and Fields
    DexFileReader.populateCallSites(fileReader); // Depends on MethodHandles
  }

  private static void populateStrings(DexFileReader reader) {
    reader.indexedItems.initializeStrings(reader.stringIDs.length);
    for (int i = 0; i < reader.stringIDs.length; i++) {
      reader.indexedItems.setString(i, reader.stringAt(i));
    }
  }

  private static void populateMethodHandles(DexFileReader reader) {
    Segment segment = reader.lookupSegment(Constants.TYPE_METHOD_HANDLE_ITEM);
    reader.indexedItems.initializeMethodHandles(segment.length);
    for (int i = 0; i < segment.length; i++) {
      reader.indexedItems.setMethodHandle(i, reader.methodHandleAt(i));
    }
  }

  private static void populateCallSites(DexFileReader reader) {
    Segment segment = reader.lookupSegment(Constants.TYPE_CALL_SITE_ID_ITEM);
    reader.indexedItems.initializeCallSites(segment.length);
    for (int i = 0; i < segment.length; i++) {
      reader.indexedItems.setCallSites(i, reader.callSiteAt(i));
    }
  }

  private static void populateTypes(DexFileReader reader) {
    Segment segment = reader.lookupSegment(Constants.TYPE_TYPE_ID_ITEM);
    reader.indexedItems.initializeTypes(segment.length);
    for (int i = 0; i < segment.length; i++) {
      reader.indexedItems.setType(i, reader.typeAt(i));
    }
  }

  private static void populateFields(DexFileReader reader) {
    Segment segment = reader.lookupSegment(Constants.TYPE_FIELD_ID_ITEM);
    reader.indexedItems.initializeFields(segment.length);
    for (int i = 0; i < segment.length; i++) {
      reader.indexedItems.setField(i, reader.fieldAt(i));
    }
  }

  private static void populateProtos(DexFileReader reader) {
    Segment segment = reader.lookupSegment(Constants.TYPE_PROTO_ID_ITEM);
    reader.indexedItems.initializeProtos(segment.length);
    for (int i = 0; i < segment.length; i++) {
      reader.indexedItems.setProto(i, reader.protoAt(i));
    }
  }

  private static void populateMethods(DexFileReader reader) {
    Segment segment = reader.lookupSegment(Constants.TYPE_METHOD_ID_ITEM);
    reader.indexedItems.initializeMethods(segment.length);
    for (int i = 0; i < segment.length; i++) {
      reader.indexedItems.setMethod(i, reader.methodAt(i));
    }
  }

  private DexString stringAt(int index) {
    final int offset = stringIDs[index];
    file.position(offset);
    int size = file.getUleb128();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    byte read;
    do {
      read = file.get();
      os.write(read);
    } while (read != 0);
    return dexItemFactory.createString(size, os.toByteArray());
  }

  private DexType typeAt(int index) {
    Segment segment = lookupSegment(Constants.TYPE_TYPE_ID_ITEM);
    if (index >= segment.length) {
      return null;
    }
    int offset = segment.offset + (Constants.TYPE_TYPE_ID_ITEM_SIZE * index);
    int stringIndex = file.getUint(offset);
    return dexItemFactory.createType(indexedItems.getString(stringIndex));
  }

  private DexField fieldAt(int index) {
    Segment segment = lookupSegment(Constants.TYPE_FIELD_ID_ITEM);
    if (index >= segment.length) {
      return null;
    }
    int offset = segment.offset + (Constants.TYPE_FIELD_ID_ITEM_SIZE * index);
    file.position(offset);
    int classIndex = file.getUshort();
    int typeIndex = file.getUshort();
    int nameIndex = file.getUint();
    DexType clazz = indexedItems.getType(classIndex);
    DexType type = indexedItems.getType(typeIndex);
    DexString name = indexedItems.getString(nameIndex);
    return dexItemFactory.createField(clazz, type, name);
  }

  private DexMethodHandle methodHandleAt(int index) {
    Segment segment = lookupSegment(Constants.TYPE_METHOD_HANDLE_ITEM);
    if (index >= segment.length) {
      return null;
    }
    int offset = segment.offset + (Constants.TYPE_METHOD_HANDLE_ITEM_SIZE * index);
    file.position(offset);
    MethodHandleType type = MethodHandleType.getKind(file.getUshort());
    file.getUshort(); // unused
    int indexFieldOrMethod = file.getUshort();
    Descriptor<? extends DexItem, ? extends Descriptor<?,?>> fieldOrMethod;
    switch (type) {
      case INSTANCE_GET:
      case INSTANCE_PUT:
      case STATIC_GET:
      case STATIC_PUT: {
        fieldOrMethod = indexedItems.getField(indexFieldOrMethod);
        break;
      }
      case INVOKE_CONSTRUCTOR:
      case INVOKE_DIRECT:
      case INVOKE_INTERFACE:
      case INVOKE_INSTANCE:
      case INVOKE_STATIC: {
        fieldOrMethod = indexedItems.getMethod(indexFieldOrMethod);
        break;
      }
      default:
        throw new AssertionError("Method handle type unsupported in a dex file.");
    }
    file.getUshort(); // unused

    return dexItemFactory.createMethodHandle(type, fieldOrMethod);
  }

  private DexCallSite callSiteAt(int index) {
    Segment segment = lookupSegment(Constants.TYPE_CALL_SITE_ID_ITEM);
    if (index >= segment.length) {
      return null;
    }
    int callSiteOffset =
        file.getUint(segment.offset + (Constants.TYPE_CALL_SITE_ID_ITEM_SIZE * index));
    DexEncodedArray callSiteEncodedArray = encodedArrayAt(callSiteOffset);
    DexValue[] values = callSiteEncodedArray.values;
    assert values[0] instanceof DexValueMethodHandle;
    assert values[1] instanceof DexValueString;
    assert values[2] instanceof DexValueMethodType;

    return dexItemFactory.createCallSite(
        ((DexValueString) values[1]).value,
        ((DexValueMethodType) values[2]).value,
        ((DexValueMethodHandle) values[0]).value,
        // 3 means first extra args
        Arrays.asList(Arrays.copyOfRange(values, 3, values.length)));
  }

  private DexProto protoAt(int index) {
    Segment segment = lookupSegment(Constants.TYPE_PROTO_ID_ITEM);
    if (index >= segment.length) {
      return null;
    }
    int offset = segment.offset + (Constants.TYPE_PROTO_ID_ITEM_SIZE * index);
    file.position(offset);
    int shortyIndex = file.getUint();
    int returnTypeIndex = file.getUint();
    int parametersOffsetIndex = file.getUint();
    DexString shorty = indexedItems.getString(shortyIndex);
    DexType returnType = indexedItems.getType(returnTypeIndex);
    DexTypeList parameters = typeListAt(parametersOffsetIndex);
    return dexItemFactory.createProto(returnType, shorty, parameters);
  }

  private DexMethod methodAt(int index) {
    Segment segment = lookupSegment(Constants.TYPE_METHOD_ID_ITEM);
    if (index >= segment.length) {
      return null;
    }
    int offset = segment.offset + (Constants.TYPE_METHOD_ID_ITEM_SIZE * index);
    file.position(offset);
    int classIndex = file.getUshort();
    int protoIndex = file.getUshort();
    int nameIndex = file.getUint();
    return dexItemFactory.createMethod(
        indexedItems.getType(classIndex),
        indexedItems.getProto(protoIndex),
        indexedItems.getString(nameIndex));
  }

  private static class AnnotationsDirectory {

    private static final DexParameterAnnotation[] NO_PARAMETER_ANNOTATIONS =
        new DexParameterAnnotation[0];

    private static final DexFieldAnnotation[] NO_FIELD_ANNOTATIONS =
        new DexFieldAnnotation[0];

    private static final DexMethodAnnotation[] NO_METHOD_ANNOTATIONS =
        new DexMethodAnnotation[0];

    private static final AnnotationsDirectory THE_EMPTY_ANNOTATIONS_DIRECTORY =
        new AnnotationsDirectory(DexAnnotationSet.empty(),
            NO_FIELD_ANNOTATIONS, new DexMethodAnnotation[0],
            NO_PARAMETER_ANNOTATIONS);

    public final DexAnnotationSet clazz;
    public final DexFieldAnnotation[] fields;
    public final DexMethodAnnotation[] methods;
    public final DexParameterAnnotation[] parameters;

    AnnotationsDirectory(DexAnnotationSet clazz,
        DexFieldAnnotation[] fields,
        DexMethodAnnotation[] methods,
        DexParameterAnnotation[] parameters) {
      this.clazz = clazz == null ? DexAnnotationSet.empty() : clazz;
      this.fields = fields == null ? NO_FIELD_ANNOTATIONS : fields;
      this.methods = methods == null ? NO_METHOD_ANNOTATIONS : methods;
      this.parameters = parameters == null ? NO_PARAMETER_ANNOTATIONS : parameters;
    }

    public static AnnotationsDirectory empty() {
      return THE_EMPTY_ANNOTATIONS_DIRECTORY;
    }
  }
}
