// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.utils.OrderedMergingIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class DexAnnotationDirectory extends DexItem {

  private final DexProgramClass clazz;
  private final List<DexEncodedMethod> methodAnnotations;
  private final List<DexEncodedMethod> parameterAnnotations;
  private final List<DexEncodedField> fieldAnnotations;
  private final boolean classHasOnlyInternalizableAnnotations;

  public DexAnnotationDirectory(DexProgramClass clazz) {
    this.clazz = clazz;
    this.classHasOnlyInternalizableAnnotations = clazz.hasOnlyInternalizableAnnotations();
    assert isSorted(clazz.directMethods());
    assert isSorted(clazz.virtualMethods());
    OrderedMergingIterator<DexEncodedMethod, DexMethod> methods =
        new OrderedMergingIterator<>(clazz.directMethods(), clazz.virtualMethods());
    methodAnnotations = new ArrayList<>();
    parameterAnnotations = new ArrayList<>();
    while (methods.hasNext()) {
      DexEncodedMethod method = methods.next();
      if (!method.annotations.isEmpty()) {
        methodAnnotations.add(method);
      }
      if (!method.parameterAnnotations.isEmpty()) {
        parameterAnnotations.add(method);
      }
    }
    assert isSorted(clazz.staticFields());
    assert isSorted(clazz.instanceFields());
    OrderedMergingIterator<DexEncodedField, DexField> fields =
        new OrderedMergingIterator<>(clazz.staticFields(), clazz.instanceFields());
    fieldAnnotations = new ArrayList<>();
    while (fields.hasNext()) {
      DexEncodedField field = fields.next();
      if (!field.annotations.isEmpty()) {
        fieldAnnotations.add(field);
      }
    }
  }

  public DexAnnotationSet getClazzAnnotations() {
    return clazz.annotations;
  }

  public List<DexEncodedMethod> getMethodAnnotations() {
    return methodAnnotations;
  }

  public List<DexEncodedMethod> getParameterAnnotations() {
    return parameterAnnotations;
  }

  public List<DexEncodedField> getFieldAnnotations() {
    return fieldAnnotations;
  }


  /**
   * DexAnnotationDirectory of a class can be canonicalized only if a clazz has annotations and
   * does not contains annotations for his fields, methods or parameters. Indeed, if a field, method
   * or parameter has annotations in this case, the DexAnnotationDirectory can not be shared since
   * it will contains information about field, method and parameters that are only related to only
   * one class.
   */
  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof DexAnnotationDirectory)) {
      return false;
    }
    if (classHasOnlyInternalizableAnnotations) {
      DexAnnotationDirectory other = (DexAnnotationDirectory) obj;
      if (!other.clazz.hasOnlyInternalizableAnnotations()) {
        return false;
      }
      return clazz.annotations.equals(other.clazz.annotations);
    }
    return super.equals(obj);
  }

  @Override
  public final int hashCode() {
    if (classHasOnlyInternalizableAnnotations) {
      return clazz.annotations.hashCode();
    }
    return super.hashCode();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection collection) {
    throw new Unreachable();
  }

  @Override
  public void collectMixedSectionItems(MixedSectionCollection collection) {
    throw new Unreachable();
  }

  private static <T extends PresortedComparable<T>> boolean isSorted(KeyedDexItem<T>[] items) {
    return isSorted(items, KeyedDexItem::getKey);
  }

  private static <S, T extends Comparable<T>> boolean isSorted(S[] items, Function<S, T> getter) {
    T current = null;
    for (S item : items) {
      T next = getter.apply(item);
      if (current != null && current.compareTo(next) >= 0) {
        return false;
      }
      current = next;
    }
    return true;
  }
}
