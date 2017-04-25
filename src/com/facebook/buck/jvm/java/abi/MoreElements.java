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

package com.facebook.buck.jvm.java.abi;

import com.google.common.base.Preconditions;
import java.util.Map;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;

/**
 * More utility functions for working with {@link Element}s, along the lines of those found on
 * {@link javax.lang.model.util.Elements}.
 */
final class MoreElements {
  private MoreElements() {}

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
        (VariableElement)
            Preconditions.checkNotNull(findAnnotationValue(retentionAnnotation, "value"));

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
        (VariableElement)
            Preconditions.checkNotNull(findAnnotationValue(retentionAnnotation, "value"));

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
