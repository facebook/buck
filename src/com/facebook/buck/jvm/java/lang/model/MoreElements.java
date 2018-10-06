/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java.lang.model;

import com.facebook.buck.util.liteinfersupport.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * More utility functions for working with {@link Element}s, along the lines of those found on
 * {@link javax.lang.model.util.Elements}.
 */
public final class MoreElements {
  private MoreElements() {}

  @Nullable
  public static TypeElement getSuperclass(TypeElement type) {
    TypeMirror superclassType = type.getSuperclass();
    switch (superclassType.getKind()) {
      case DECLARED:
      case ERROR:
        return (TypeElement) ((DeclaredType) superclassType).asElement();
      case NONE:
        return null;
        // $CASES-OMITTED$
      default:
        throw new IllegalArgumentException(superclassType.toString());
    }
  }

  public static Stream<TypeElement> getInterfaces(TypeElement type) {
    return type.getInterfaces()
        .stream()
        .filter(it -> it.getKind() == TypeKind.DECLARED || it.getKind() == TypeKind.ERROR)
        .map(it -> (TypeElement) ((DeclaredType) it).asElement());
  }

  public static Stream<TypeElement> getTransitiveSuperclasses(TypeElement type) {
    Stream.Builder<TypeElement> builder = Stream.builder();

    TypeElement walker = getSuperclass(type);
    while (walker != null) {
      builder.add(walker);
      walker = getSuperclass(walker);
    }

    return builder.build();
  }

  public static boolean isTransitiveMemberClass(TypeElement inner, TypeElement outer) {
    Element walker = inner.getEnclosingElement();
    while (walker != null) {
      if (walker == outer) {
        return true;
      }
      walker = walker.getEnclosingElement();
    }

    return false;
  }

  public static TypeElement getTypeElement(Element element) {
    if (element.getKind() == ElementKind.PACKAGE) {
      throw new IllegalArgumentException();
    }

    Element walker = element;
    while (!walker.getKind().isClass() && !walker.getKind().isInterface()) {
      walker = Objects.requireNonNull(walker.getEnclosingElement());
    }

    return (TypeElement) walker;
  }

  public static TypeElement getTopLevelTypeElement(Element element) {
    if (element.getKind() == ElementKind.PACKAGE) {
      throw new IllegalArgumentException();
    }

    Element walker = element;
    while (walker.getEnclosingElement() != null
        && walker.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
      walker = Objects.requireNonNull(walker.getEnclosingElement());
    }

    return (TypeElement) walker;
  }

  public static PackageElement getPackageElement(Element element) {
    Element walker = element;
    while (walker.getKind() != ElementKind.PACKAGE) {
      walker = Objects.requireNonNull(walker.getEnclosingElement());
    }

    return (PackageElement) walker;
  }

  public static boolean isInnerClassConstructor(ExecutableElement e) {
    return isConstructor(e) && isInnerClass((TypeElement) e.getEnclosingElement());
  }

  public static boolean isConstructor(ExecutableElement e) {
    return e.getSimpleName().contentEquals("<init>");
  }

  public static boolean isInnerClass(TypeElement e) {
    return e.getNestingKind() == NestingKind.MEMBER && !e.getModifiers().contains(Modifier.STATIC);
  }

  public static Element getOuterClass(ExecutableElement e) {
    Element innerClass = e.getEnclosingElement();
    Element outerClass = innerClass.getEnclosingElement();
    if (outerClass == null) {
      throw new IllegalArgumentException(
          String.format("Cannot get outer class of element which isn't an inner class: %s", e));
    }
    return outerClass;
  }

  public static boolean isRuntimeRetention(AnnotationMirror annotation) {
    DeclaredType annotationType = annotation.getAnnotationType();
    TypeElement annotationTypeElement = (TypeElement) annotationType.asElement();

    AnnotationMirror retentionAnnotation =
        findAnnotation("java.lang.annotation.Retention", annotationTypeElement);
    if (retentionAnnotation == null) {
      return false;
    }

    VariableElement retentionPolicy =
        (VariableElement) Objects.requireNonNull(findAnnotationValue(retentionAnnotation, "value"));

    return retentionPolicy.getSimpleName().contentEquals("RUNTIME");
  }

  public static boolean isSourceRetention(AnnotationMirror annotation) {
    DeclaredType annotationType = annotation.getAnnotationType();
    TypeElement annotationTypeElement = (TypeElement) annotationType.asElement();

    AnnotationMirror retentionAnnotation =
        findAnnotation("java.lang.annotation.Retention", annotationTypeElement);
    if (retentionAnnotation == null) {
      return false;
    }

    VariableElement retentionPolicy =
        (VariableElement) Objects.requireNonNull(findAnnotationValue(retentionAnnotation, "value"));

    return retentionPolicy.getSimpleName().contentEquals("SOURCE");
  }

  @Nullable
  private static AnnotationMirror findAnnotation(CharSequence name, Element element) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      TypeElement annotationTypeElement = (TypeElement) annotationType.asElement();

      if (annotationTypeElement.getQualifiedName().contentEquals(name)) {
        return annotationMirror;
      }
    }

    return null;
  }

  @Nullable
  private static Object findAnnotationValue(AnnotationMirror annotation, String name) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        annotation.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(name)) {
        return entry.getValue().getValue();
      }
    }

    return null;
  }
}
