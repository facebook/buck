// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.Resource.Origin;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.utils.ProgramResource;
import com.android.tools.r8.utils.ProgramResource.Kind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class DexProgramClass extends DexClass implements Supplier<DexProgramClass> {

  private static DexEncodedArray SENTINEL_NOT_YET_COMPUTED = new DexEncodedArray(new DexValue[0]);

  private final ProgramResource.Kind originKind;
  private DexEncodedArray staticValues = SENTINEL_NOT_YET_COMPUTED;
  private final Collection<DexProgramClass> synthesizedFrom;
  private int classFileVersion = -1;

  public DexProgramClass(
      DexType type,
      ProgramResource.Kind originKind,
      Origin origin,
      ClassAccessFlags accessFlags,
      DexType superType,
      DexTypeList interfaces,
      DexString sourceFile,
      DexAnnotationSet classAnnotations,
      DexEncodedField[] staticFields,
      DexEncodedField[] instanceFields,
      DexEncodedMethod[] directMethods,
      DexEncodedMethod[] virtualMethods) {
    this(
        type,
        originKind,
        origin,
        accessFlags,
        superType,
        interfaces,
        sourceFile,
        classAnnotations,
        staticFields,
        instanceFields,
        directMethods,
        virtualMethods,
        Collections.emptyList());
  }

  public DexProgramClass(
      DexType type,
      ProgramResource.Kind originKind,
      Origin origin,
      ClassAccessFlags accessFlags,
      DexType superType,
      DexTypeList interfaces,
      DexString sourceFile,
      DexAnnotationSet classAnnotations,
      DexEncodedField[] staticFields,
      DexEncodedField[] instanceFields,
      DexEncodedMethod[] directMethods,
      DexEncodedMethod[] virtualMethods,
      Collection<DexProgramClass> synthesizedDirectlyFrom) {
    super(sourceFile, interfaces, accessFlags, superType, type, staticFields,
        instanceFields, directMethods, virtualMethods, classAnnotations, origin);
    assert classAnnotations != null;
    this.originKind = originKind;
    this.synthesizedFrom = accumulateSynthesizedFrom(new HashSet<>(), synthesizedDirectlyFrom);
  }

  public boolean originatesFromDexResource() {
    return originKind == Kind.DEX;
  }

  public boolean originatesFromClassResource() {
    return originKind == Kind.CLASS;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    if (indexedItems.addClass(this)) {
      type.collectIndexedItems(indexedItems);
      if (superType != null) {
        superType.collectIndexedItems(indexedItems);
      } else {
        assert type.toDescriptorString().equals("Ljava/lang/Object;");
      }
      if (sourceFile != null) {
        sourceFile.collectIndexedItems(indexedItems);
      }
      if (annotations != null) {
        annotations.collectIndexedItems(indexedItems);
      }
      if (interfaces != null) {
        interfaces.collectIndexedItems(indexedItems);
      }
      synchronizedCollectAll(indexedItems, staticFields);
      synchronizedCollectAll(indexedItems, instanceFields);
      synchronizedCollectAll(indexedItems, directMethods);
      synchronizedCollectAll(indexedItems, virtualMethods);
    }
  }

  private static <T extends DexItem> void synchronizedCollectAll(IndexedItemCollection collection,
      T[] items) {
    synchronized (items) {
      collectAll(collection, items);
    }
  }

  public Collection<DexProgramClass> getSynthesizedFrom() {
    return synthesizedFrom;
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    if (hasAnnotations()) {
      mixedItems.setAnnotationsDirectoryForClass(this, new DexAnnotationDirectory(this));
    }
  }

  @Override
  public void addDependencies(MixedSectionCollection collector) {
    // We only have a class data item if there are methods or fields.
    if (hasMethodsOrFields()) {
      collector.add(this);
      synchronizedCollectAll(collector, directMethods);
      synchronizedCollectAll(collector, virtualMethods);
      synchronizedCollectAll(collector, staticFields);
      synchronizedCollectAll(collector, instanceFields);
    }
    if (annotations != null) {
      annotations.collectMixedSectionItems(collector);
    }
    if (interfaces != null) {
      interfaces.collectMixedSectionItems(collector);
    }
    annotations.collectMixedSectionItems(collector);
  }

  private static <T extends DexItem> void synchronizedCollectAll(MixedSectionCollection collection,
      T[] items) {
    synchronized (items) {
      collectAll(collection, items);
    }
  }

  @Override
  public String toString() {
    return type.toString();
  }

  @Override
  public String toSourceString() {
    return type.toSourceString();
  }

  @Override
  public boolean isProgramClass() {
    return true;
  }

  @Override
  public DexProgramClass asProgramClass() {
    return this;
  }

  public boolean hasMethodsOrFields() {
    int numberOfFields = staticFields().length + instanceFields().length;
    int numberOfMethods = directMethods().length + virtualMethods().length;
    return numberOfFields + numberOfMethods > 0;
  }

  public boolean hasAnnotations() {
    return !annotations.isEmpty()
        || hasAnnotations(virtualMethods)
        || hasAnnotations(directMethods)
        || hasAnnotations(staticFields)
        || hasAnnotations(instanceFields);
  }

  boolean hasOnlyInternalizableAnnotations() {
    return !hasAnnotations(virtualMethods)
        && !hasAnnotations(directMethods)
        && !hasAnnotations(staticFields)
        && !hasAnnotations(instanceFields);
  }

  private boolean hasAnnotations(DexEncodedField[] fields) {
    synchronized (fields) {
      return Arrays.stream(fields).anyMatch(DexEncodedField::hasAnnotation);
    }
  }

  private boolean hasAnnotations(DexEncodedMethod[] methods) {
    synchronized (methods) {
      return Arrays.stream(methods).anyMatch(DexEncodedMethod::hasAnnotation);
    }
  }

  private static Collection<DexProgramClass> accumulateSynthesizedFrom(
      Set<DexProgramClass> accumulated,
      Collection<DexProgramClass> toAccumulate) {
    for (DexProgramClass dexProgramClass : toAccumulate) {
      if (dexProgramClass.synthesizedFrom.isEmpty()) {
        accumulated.add(dexProgramClass);
      } else {
        accumulateSynthesizedFrom(accumulated, dexProgramClass.synthesizedFrom);
      }
    }
    return accumulated;
  }

  public void computeStaticValues(DexItemFactory factory) {
    // It does not actually hurt to compute this multiple times. So racing on staticValues is OK.
    if (staticValues == SENTINEL_NOT_YET_COMPUTED) {
      synchronized (staticFields) {
        assert PresortedComparable.isSorted(staticFields);
        DexEncodedField[] fields = staticFields;
        int length = 0;
        List<DexValue> values = new ArrayList<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
          DexEncodedField field = fields[i];
          assert field.staticValue != null;
          values.add(field.staticValue);
          if (!field.staticValue.isDefault(field.field.type, factory)) {
            length = i + 1;
          }
        }
        if (length > 0) {
          staticValues = new DexEncodedArray(
              values.subList(0, length).toArray(new DexValue[length]));
        } else {
          staticValues = null;
        }
      }
    }
  }

  public DexEncodedArray getStaticValues() {
    // The sentinel value is left over for classes that actually have no fields.
    if (staticValues == SENTINEL_NOT_YET_COMPUTED) {
      assert !hasMethodsOrFields();
      return null;
    }
    return staticValues;
  }

  public void addVirtualMethod(DexEncodedMethod virtualMethod) {
    assert !virtualMethod.accessFlags.isStatic();
    assert !virtualMethod.accessFlags.isPrivate();
    assert !virtualMethod.accessFlags.isConstructor();
    synchronized (virtualMethods) {
      virtualMethods = Arrays.copyOf(virtualMethods, virtualMethods.length + 1);
      virtualMethods[virtualMethods.length - 1] = virtualMethod;
    }
  }

  public void addStaticMethod(DexEncodedMethod staticMethod) {
    assert staticMethod.accessFlags.isStatic();
    assert !staticMethod.accessFlags.isPrivate();
    synchronized (directMethods) {
      directMethods = Arrays.copyOf(directMethods, directMethods.length + 1);
      directMethods[directMethods.length - 1] = staticMethod;
    }
  }

  public void sortMembers() {
    sortEncodedFields(staticFields);
    sortEncodedFields(instanceFields);
    sortEncodedMethods(directMethods);
    sortEncodedMethods(virtualMethods);
  }

  private void sortEncodedFields(DexEncodedField[] fields) {
    synchronized (fields) {
      Arrays.sort(fields, Comparator.comparing(a -> a.field));
    }
  }

  private void sortEncodedMethods(DexEncodedMethod[] methods) {
    synchronized (methods) {
      Arrays.sort(methods, Comparator.comparing(a -> a.method));
    }
  }

  @Override
  public DexProgramClass get() {
    return this;
  }

  public void setClassFileVersion(int classFileVersion) {
    this.classFileVersion = classFileVersion;
  }

  public int getClassFileVersion() {
    assert classFileVersion != -1;
    return classFileVersion;
  }
}
