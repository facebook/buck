// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexValue.DexValueAnnotation;
import com.android.tools.r8.graph.DexValue.DexValueArray;
import com.android.tools.r8.graph.DexValue.DexValueInt;
import com.android.tools.r8.graph.DexValue.DexValueMethod;
import com.android.tools.r8.graph.DexValue.DexValueNull;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.graph.DexValue.DexValueType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class DexAnnotation extends DexItem {
  public static final int VISIBILITY_BUILD = 0x00;
  public static final int VISIBILITY_RUNTIME = 0x01;
  public static final int VISIBILITY_SYSTEM = 0x02;
  public final int visibility;
  public final DexEncodedAnnotation annotation;

  public DexAnnotation(int visibility, DexEncodedAnnotation annotation) {
    this.visibility = visibility;
    this.annotation = annotation;
  }

  @Override
  public int hashCode() {
    return visibility + annotation.hashCode() * 3;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof DexAnnotation) {
      DexAnnotation o = (DexAnnotation) other;
      return (visibility == o.visibility) && annotation.equals(o.annotation);
    }
    return false;
  }

  @Override
  public String toString() {
    return visibility + " " + annotation;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    annotation.collectIndexedItems(indexedItems);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    mixedItems.add(this);
  }

  public static DexAnnotation createEnclosingClassAnnotation(DexType enclosingClass,
      DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationEnclosingClass, factory,
        new DexValueType(enclosingClass));
  }

  public static DexAnnotation createEnclosingMethodAnnotation(DexMethod enclosingMethod,
      DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationEnclosingMethod, factory,
        new DexValueMethod(enclosingMethod));
  }

  public static boolean isEnclosingClassAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationEnclosingClass;
  }

  public static boolean isEnclosingMethodAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationEnclosingMethod;
  }

  public static boolean isInnerClassesAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationMemberClasses
        || annotation.annotation.type == factory.annotationInnerClass;
  }

  public static DexAnnotation createInnerClassAnnotation(String clazz, int access,
      DexItemFactory factory) {
    return new DexAnnotation(VISIBILITY_SYSTEM,
        new DexEncodedAnnotation(factory.annotationInnerClass,
            new DexAnnotationElement[]{
                new DexAnnotationElement(
                    factory.createString("accessFlags"),
                    DexValueInt.create(access)),
                new DexAnnotationElement(
                    factory.createString("name"),
                    (clazz == null)
                        ? DexValueNull.NULL
                        : new DexValueString(factory.createString(clazz)))
            }));
  }

  public static DexAnnotation createMemberClassesAnnotation(List<DexType> classes,
      DexItemFactory factory) {
    DexValue[] values = new DexValue[classes.size()];
    for (int i = 0; i < classes.size(); i++) {
      values[i] = new DexValueType(classes.get(i));
    }
    return createSystemValueAnnotation(factory.annotationMemberClasses, factory,
        new DexValueArray(values));
  }

  public static DexAnnotation createSourceDebugExtensionAnnotation(DexValue value,
      DexItemFactory factory) {
    return new DexAnnotation(VISIBILITY_SYSTEM,
        new DexEncodedAnnotation(factory.annotationSourceDebugExtension,
            new DexAnnotationElement[] {
              new DexAnnotationElement(factory.createString("value"), value)
            }));
  }

  public static DexAnnotation createMethodParametersAnnotation(DexValue[] names,
      DexValue[] accessFlags, DexItemFactory factory) {
    assert names.length == accessFlags.length;
    return new DexAnnotation(VISIBILITY_SYSTEM,
        new DexEncodedAnnotation(factory.annotationMethodParameters,
            new DexAnnotationElement[]{
                new DexAnnotationElement(
                    factory.createString("names"),
                    new DexValueArray(names)),
                new DexAnnotationElement(
                    factory.createString("accessFlags"),
                    new DexValueArray(accessFlags))
            }));
  }

  public static DexAnnotation createAnnotationDefaultAnnotation(DexType type,
      List<DexAnnotationElement> defaults, DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationDefault, factory,
        new DexValueAnnotation(
            new DexEncodedAnnotation(type,
                defaults.toArray(new DexAnnotationElement[defaults.size()])))
    );
  }

  public static DexAnnotation createSignatureAnnotation(String signature, DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationSignature, factory,
        compressSignature(signature, factory));
  }

  public static DexAnnotation createThrowsAnnotation(DexValue[] exceptions,
      DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationThrows, factory,
        new DexValueArray(exceptions));
  }

  private static DexAnnotation createSystemValueAnnotation(DexType type, DexItemFactory factory,
      DexValue value) {
    return new DexAnnotation(VISIBILITY_SYSTEM,
        new DexEncodedAnnotation(type, new DexAnnotationElement[]{
            new DexAnnotationElement(factory.createString("value"), value)
        }));
  }

  public static boolean isThrowingAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationThrows;
  }

  public static boolean isSignatureAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationSignature;
  }


  public static boolean isSourceDebugExtension(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationSourceDebugExtension;
  }

  /**
   * As a simple heuristic for compressing a signature by splitting on fully qualified class names
   * and make them individual part. All other parts of the signature are simply grouped and separate
   * the names.
   * For examples, "()Ljava/lang/List<Lfoo/bar/Baz;>;" splits into:
   * <pre>
   *   ["()", "Ljava/lang/List<", "Lfoo/bar/Baz;", ">;"]
   * </pre>
   */
  private static DexValue compressSignature(String signature, DexItemFactory factory) {
    final int length = signature.length();
    List<DexValue> parts = new ArrayList<>();

    for (int at = 0; at < length; /*at*/) {
      char c = signature.charAt(at);
      int endAt = at + 1;
      if (c == 'L') {
        // Scan to ';' or '<' and consume them.
        while (endAt < length) {
          c = signature.charAt(endAt);
          if (c == ';' || c == '<') {
            endAt++;
            break;
          }
          endAt++;
        }
      } else {
        // Scan to 'L' without consuming it.
        while (endAt < length) {
          c = signature.charAt(endAt);
          if (c == 'L') {
            break;
          }
          endAt++;
        }
      }

      parts.add(toDexValue(signature.substring(at, endAt), factory));
      at = endAt;
    }

    return new DexValueArray(parts.toArray(new DexValue[parts.size()]));
  }

  private static DexValue toDexValue(String string, DexItemFactory factory) {
    return new DexValueString(factory.createString(string));
  }

  public static Collection<DexType> readAnnotationSynthesizedClassMap(
      DexProgramClass programClass,
      DexItemFactory dexItemFactory) {
    DexAnnotation foundAnnotation = programClass.annotations.getFirstMatching(
        dexItemFactory.annotationSynthesizedClassMap);
    if (foundAnnotation != null) {
      if (foundAnnotation.annotation.elements.length != 1) {
        throw new CompilationError(
            getInvalidSynthesizedClassMapMessage(programClass, foundAnnotation));
      }
      DexAnnotationElement value = foundAnnotation.annotation.elements[0];
      if (!value.name.toSourceString().equals("value")) {
        throw new CompilationError(
            getInvalidSynthesizedClassMapMessage(programClass, foundAnnotation));
      }
      if (!(value.value instanceof DexValueArray)) {
        throw new CompilationError(
            getInvalidSynthesizedClassMapMessage(programClass, foundAnnotation));
      }
      DexValueArray existingList = (DexValueArray) value.value;
      Collection<DexType> synthesized = new ArrayList<>(existingList.values.length);
      for (DexValue element : existingList.getValues()) {
        if (!(element instanceof DexValueType)) {
          throw new CompilationError(
              getInvalidSynthesizedClassMapMessage(programClass, foundAnnotation));
        }
        synthesized.add(((DexValueType) element).value);
      }
      return synthesized;
    }
    return Collections.emptyList();
  }

  private static String getInvalidSynthesizedClassMapMessage(
      DexProgramClass annotatedClass,
      DexAnnotation invalidAnnotation) {
    return annotatedClass.toSourceString()
        + " is annotated with invalid "
        + invalidAnnotation.annotation.type.toString()
        + ": " + invalidAnnotation.toString();
  }

  public static DexAnnotation createAnnotationSynthesizedClassMap(
      Set<DexType> synthesized,
      DexItemFactory dexItemFactory) {
    DexValueType[] values = synthesized.stream()
        .map(type -> new DexValueType(type))
        .toArray(length -> new DexValueType[length]);
    DexValueArray array = new DexValueArray(values);
    DexAnnotationElement pair =
        new DexAnnotationElement(dexItemFactory.createString("value"), array);
    return new DexAnnotation(
        VISIBILITY_BUILD,
        new DexEncodedAnnotation(
            dexItemFactory.annotationSynthesizedClassMap, new DexAnnotationElement[]{pair}));
  }
}
