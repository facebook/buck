/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * An implementation of {@link Elements} using just the AST of a single module, without its
 * dependencies. Of necessity, such an implementation will need to make assumptions about the
 * meanings of some names, and thus must be used with care. See documentation for individual methods
 * and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedElements implements Elements {
  private final Elements javacElements;
  private final Map<Element, TreeBackedElement> treeBackedElements = new HashMap<>();
  private final Map<Name, TypeElement> knownTypes = new HashMap<>();
  private final Map<Name, PackageElement> knownPackages = new HashMap<>();

  public TreeBackedElements(Elements javacElements) {
    this.javacElements = javacElements;
  }

  /* package */ void clear() {
    treeBackedElements.clear();
    knownTypes.clear();
    knownPackages.clear();
  }

  public <UnderlyingElement extends Element, WrappedElement extends TreeBackedElement>
      WrappedElement enterElement(
          UnderlyingElement underlyingElement,
          Function<UnderlyingElement, WrappedElement> constructor) {
    @SuppressWarnings("unchecked") // This function is the only one that inserts to this map
    WrappedElement result = (WrappedElement) treeBackedElements.get(underlyingElement);
    if (result != null) {
      return result;
    }

    result = constructor.apply(underlyingElement);
    treeBackedElements.put(underlyingElement, result);
    if (result instanceof TypeElement) {
      TypeElement typeElement = (TypeElement) result;
      knownTypes.put(typeElement.getQualifiedName(), typeElement);
    } else if (result instanceof TreeBackedPackageElement) {
      TreeBackedPackageElement packageElement = (TreeBackedPackageElement) result;
      knownPackages.put(packageElement.getQualifiedName(), packageElement);
    }
    return result;
  }

  @Nullable
  /* package */ PackageElement getCanonicalElement(@Nullable PackageElement element) {
    return (PackageElement) getCanonicalElement((Element) element);
  }

  @Nullable
  /* package */ TypeElement getCanonicalElement(@Nullable TypeElement element) {
    return (TypeElement) getCanonicalElement((Element) element);
  }

  @Nullable
  /* package */ ExecutableElement getCanonicalElement(@Nullable ExecutableElement element) {
    return (ExecutableElement) getCanonicalElement((Element) element);
  }

  /**
   * Given a javac Element, gets the element that should be used by callers to refer to it. For
   * elements that have ASTs, that will be a TreeBackedElement; otherwise the javac Element itself.
   */
  @Nullable
  /* package */ Element getCanonicalElement(@Nullable Element element) {
    Element result = treeBackedElements.get(element);
    if (result == null) {
      result = element;
    }

    return result;
  }

  /* package */ Element[] getJavacElements(Element[] elements) {
    return Arrays.stream(elements).map(this::getJavacElement).toArray(Element[]::new);
  }

  /* package */ TypeElement getJavacElement(TypeElement element) {
    return (TypeElement) getJavacElement((Element) element);
  }

  /* package */ Element getJavacElement(Element element) {
    if (element instanceof TreeBackedElement) {
      TreeBackedElement treeBackedElement = (TreeBackedElement) element;
      return treeBackedElement.getUnderlyingElement();
    }

    return element;
  }

  /**
   * Gets the package element with the given name. If a package with the given name is referenced in
   * the code or exists in the classpath, returns the corresponding element. Otherwise returns null.
   */
  @Override
  @Nullable
  public PackageElement getPackageElement(CharSequence qualifiedNameString) {
    Name qualifiedName = getName(qualifiedNameString);

    if (!knownPackages.containsKey(qualifiedName)) {
      PackageElement javacElement = javacElements.getPackageElement(qualifiedName);
      if (javacElement != null) {
        // If none of the packages for which we have parse trees matches this fully-qualified name,
        // ask javac. javac will check the classpath, which will pick up built-ins (like java.lang)
        // and any packages from dependency targets that are already compiled and on the classpath.
        // Because our tree-backed elements and javac's elements are sharing a name table, we
        // should be able to mix implementations without causing too much trouble.
        knownPackages.put(qualifiedName, javacElement);
      }
    }

    return knownPackages.get(qualifiedName);
  }

  /**
   * Gets the type element with the given name. If a class with the given name is referenced in the
   * code or exists in the classpath, returns the corresponding element. Otherwise returns null.
   */
  @Override
  @Nullable
  public TypeElement getTypeElement(CharSequence fullyQualifiedCharSequence) {
    Name fullyQualifiedName = getName(fullyQualifiedCharSequence);
    if (!knownTypes.containsKey(fullyQualifiedName)) {
      // If none of the types for which we have parse trees matches this fully-qualified name,
      // ask javac. javac will check the classpath, which will pick up built-ins (like java.lang)
      // and any types from dependency targets that are already compiled and on the classpath.
      // Because our tree-backed elements and javac's elements are sharing a name table, we
      // should be able to mix implementations without causing too much trouble.
      TypeElement javacElement = javacElements.getTypeElement(fullyQualifiedName);
      if (javacElement != null) {
        knownTypes.put(fullyQualifiedName, javacElement);
      }
    }

    return knownTypes.get(fullyQualifiedName);
  }

  @Override
  public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(
      AnnotationMirror a) {

    Map<ExecutableElement, AnnotationValue> result = new HashMap<>();

    result.putAll(a.getElementValues());

    TypeElement annotationType = (TypeElement) a.getAnnotationType().asElement();
    List<ExecutableElement> parameters =
        ElementFilter.methodsIn(annotationType.getEnclosedElements());
    for (ExecutableElement parameter : parameters) {
      if (!result.containsKey(parameter) && parameter.getDefaultValue() != null) {
        result.put(parameter, parameter.getDefaultValue());
      }
    }

    return result;
  }

  @Override
  @Nullable
  public String getDocComment(Element e) {
    return javacElements.getDocComment(getJavacElement(e));
  }

  @Override
  public boolean isDeprecated(Element e) {
    return javacElements.isDeprecated(getJavacElement(e));
  }

  @Override
  public Name getBinaryName(TypeElement type) {
    return javacElements.getBinaryName(getJavacElement(type));
  }

  @Override
  public PackageElement getPackageOf(Element type) {
    return Preconditions.checkNotNull(
        getCanonicalElement(javacElements.getPackageOf(getJavacElement(type))));
  }

  @Override
  public List<? extends Element> getAllMembers(TypeElement type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<? extends AnnotationMirror> getAllAnnotationMirrors(Element e) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hides(Element hider, Element hidden) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean overrides(
      ExecutableElement overrider, ExecutableElement overridden, TypeElement type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getConstantExpression(Object value) {
    return javacElements.getConstantExpression(value);
  }

  @Override
  public void printElements(Writer w, Element... elements) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Name getName(CharSequence cs) {
    return javacElements.getName(cs);
  }

  @Override
  public boolean isFunctionalInterface(TypeElement type) {
    throw new UnsupportedOperationException();
  }
}
