// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.CompilationError;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.Reference2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ObjectToOffsetMapping {

  private final static int NOT_FOUND = -1;
  private final static int NOT_SET = -2;

  private final DexProgramClass[] classes;
  private final Reference2IntMap<DexProto> protos;
  private final Reference2IntMap<DexType> types;
  private final Reference2IntMap<DexMethod> methods;
  private final Reference2IntMap<DexField> fields;
  private final Reference2IntMap<DexString> strings;
  private final Reference2IntMap<DexCallSite> callSites;
  private final Reference2IntMap<DexMethodHandle> methodHandles;
  private DexString firstJumboString;

  public ObjectToOffsetMapping(
      DexApplication application,
      Collection<DexProgramClass> classes,
      Collection<DexProto> protos,
      Collection<DexType> types,
      Collection<DexMethod> methods,
      Collection<DexField> fields,
      Collection<DexString> strings,
      Collection<DexCallSite> callSites,
      Collection<DexMethodHandle> methodHandles) {
    assert application != null;
    assert classes != null;
    assert protos != null;
    assert types != null;
    assert methods != null;
    assert fields != null;
    assert strings != null;
    assert callSites != null;
    assert methodHandles != null;

    this.classes = sortClasses(application, classes);
    this.protos = createMap(protos, true, this::failOnOverflow);
    this.types = createMap(types, true, this::failOnOverflow);
    this.methods = createMap(methods, true, this::failOnOverflow);
    this.fields = createMap(fields, true, this::failOnOverflow);
    this.strings = createMap(strings, true, this::setFirstJumboString);
    // No need to sort CallSite, they will be written in data section in the callSites order,
    // consequently offset of call site used into the call site section will be in ascending order.
    this.callSites = createMap(callSites, false, this::failOnOverflow);
    // No need to sort method handle
    this.methodHandles = createMap(methodHandles, false, this::failOnOverflow);
  }

  private void setFirstJumboString(DexString string) {
    assert firstJumboString == null;
    firstJumboString = string;
  }

  private void failOnOverflow(DexItem item) {
    throw new CompilationError("Index overflow for " + item.getClass());
  }

  private <T extends IndexedDexItem> Reference2IntMap<T> createMap(Collection<T> items,
      boolean sort, Consumer<T> onUInt16Overflow) {
    if (items.isEmpty()) {
      return null;
    }
    Reference2IntMap<T> map = new Reference2IntLinkedOpenHashMap<>(items.size());
    map.defaultReturnValue(NOT_FOUND);
    Collection<T> sorted = sort ? items.stream().sorted().collect(Collectors.toList()) : items;
    int index = 0;
    for (T item : sorted) {
      if (index == Constants.U16BIT_MAX + 1) {
        onUInt16Overflow.accept(item);
      }
      map.put(item, index++);
    }
    return map;
  }

  private static DexProgramClass[] sortClasses(DexApplication application,
      Collection<DexProgramClass> classes) {
    SortingProgramClassVisitor classVisitor = new SortingProgramClassVisitor(application, classes);
    // Collect classes in subtyping order, based on a sorted list of classes to start with.
    classVisitor.run(
        classes.stream().sorted(Comparator.comparing(DexClass::getType))
            .collect(Collectors.toList()));
    return classVisitor.getSortedClasses();
  }

  private static <T> Collection<T> keysOrEmpty(Map<T, ?> map) {
    return map == null ? Collections.emptyList() : map.keySet();
  }

  public Collection<DexMethod> getMethods() {
    return keysOrEmpty(methods);
  }

  public DexProgramClass[] getClasses() {
    return classes;
  }

  public Collection<DexType> getTypes() {
    return keysOrEmpty(types);
  }

  public Collection<DexProto> getProtos() {
    return keysOrEmpty(protos);
  }

  public Collection<DexField> getFields() {
    return keysOrEmpty(fields);
  }

  public Collection<DexString> getStrings() {
    return keysOrEmpty(strings);
  }

  public Collection<DexCallSite> getCallSites() {
    return keysOrEmpty(callSites);
  }

  public Collection<DexMethodHandle> getMethodHandles() {
    return keysOrEmpty(methodHandles);
  }

  public boolean hasJumboStrings() {
    return firstJumboString != null;
  }

  public DexString getFirstJumboString() {
    return firstJumboString;
  }

  private <T extends IndexedDexItem> int getOffsetFor(T item, Reference2IntMap<T> map) {
    int index = map.getInt(item);
    assert index != NOT_SET : "Index was not set: " + item;
    assert index != NOT_FOUND : "Missing dependency: " + item;
    return index;
  }

  public int getOffsetFor(DexProto proto) {
    return getOffsetFor(proto, protos);
  }

  public int getOffsetFor(DexField field) {
    return getOffsetFor(field, fields);
  }

  public int getOffsetFor(DexMethod method) {
    return getOffsetFor(method, methods);
  }

  public int getOffsetFor(DexString string) {
    return getOffsetFor(string, strings);
  }

  public int getOffsetFor(DexType type) {
    return getOffsetFor(type, types);
  }

  public int getOffsetFor(DexCallSite callSite) {
    return getOffsetFor(callSite, callSites);
  }

  public int getOffsetFor(DexMethodHandle methodHandle) {
    return getOffsetFor(methodHandle, methodHandles);
  }

  private static class SortingProgramClassVisitor extends ProgramClassVisitor {
    private final Set<DexClass> classSet = Sets.newIdentityHashSet();
    private final DexProgramClass[] sortedClasses;

    private int index = 0;

    SortingProgramClassVisitor(DexApplication application,
        Collection<DexProgramClass> classes) {
      super(application);
      this.sortedClasses = new DexProgramClass[classes.size()];
      classSet.addAll(classes);
    }

    @Override
    public void visit(DexType type) {}

    @Override
    public void visit(DexClass clazz) {
      if (classSet.contains(clazz)) {
        assert index < sortedClasses.length;
        sortedClasses[index++] = (DexProgramClass) clazz;
      }
    }

    DexProgramClass[] getSortedClasses() {
      assert index == sortedClasses.length;
      return sortedClasses;
    }
  }
}
