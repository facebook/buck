/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.lang.model;

import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.util.liteinfersupport.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import javax.lang.model.util.Elements;

/**
 * More utility functions for working with {@link Element}s, along the lines of those found on
 * {@link javax.lang.model.util.Elements}.
 */
public final class MoreElements {
  private static Method getAllPackageElementsMethod;

  static {
    try {
      if (JavaVersion.getMajorVersion() >= 9) {
        getAllPackageElementsMethod =
            Elements.class.getMethod("getAllPackageElements", CharSequence.class);
      }
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  private MoreElements() {}

  /** Get a package by name, even if it has no members. */
  @Nullable
  public static PackageElement getPackageElementEvenIfEmpty(
      Elements elements, CharSequence packageName) {
    if (JavaVersion.getMajorVersion() <= 8) {
      // In Java 8 and earlier, {@link
      // javax.lang.model.util.Elements#getPackageElement(CharSequence)} returns
      // packages even if they have no members.
      return elements.getPackageElement(packageName);
    } else {
      try {
        // In Java 9 and later, only the new {@link
        // javax.lang.model.util.Elements#getPackageElement(ModuleElement, CharSequence)},
        // which {@link javax.lang.mode.util.Elements#getAllPackageElements} calls, returns empty
        // packages.
        @SuppressWarnings("unchecked")
        Set<? extends PackageElement> packages =
            (Set<? extends PackageElement>)
                getAllPackageElementsMethod.invoke(elements, packageName);
        if (packages.isEmpty()) {
          return null;
        }
        if (packages.size() > 1) {
          // TODO(jtorkkola): Add proper Java 9+ module support.
          throw new IllegalStateException("Found more than one package matching " + packageName);
        }
        return packages.iterator().next();
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }

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
    return type.getInterfaces().stream()
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
