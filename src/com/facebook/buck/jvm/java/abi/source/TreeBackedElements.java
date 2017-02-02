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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * An implementation of {@link Elements} using just the AST of a single module, without its
 * dependencies. Of necessity, such an implementation will need to make assumptions about the
 * meanings of some names, and thus must be used with care. See documentation for individual
 * methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedElements implements Elements {
  private final Elements javacElements;
  private final Map<Name, TypeElement> knownTypes = new HashMap<>();
  private final Map<Name, TreeBackedPackageElement> knownPackages = new HashMap<>();

  @Nullable
  private TypeResolverFactory resolverFactory;

  public TreeBackedElements(Elements javacElements) {
    this.javacElements = javacElements;
  }

  /* package */ void setResolverFactory(TypeResolverFactory resolverFactory) {
    this.resolverFactory = resolverFactory;
  }

  /**
   * Gets the package element with the given name. If a package with the given name is referenced
   * in the code or exists in the classpath, returns the corresponding element. Otherwise returns
   * null.
   */
  @Override
  @Nullable
  public TreeBackedPackageElement getPackageElement(CharSequence qualifiedNameString) {
    Name qualifiedName = getName(qualifiedNameString);

    if (!knownPackages.containsKey(qualifiedName)) {
      PackageElement javacPackageElement = javacElements.getPackageElement(qualifiedName);
      if (javacPackageElement != null) {
        // We can lazily discover packages that are known to javac
        return getOrCreatePackageElement(qualifiedNameString);
      }
    }

    return knownPackages.get(qualifiedName);
  }

  /* package */ TreeBackedPackageElement getOrCreatePackageElement(
      CharSequence qualifiedNameString) {
    Name qualifiedName = getName(qualifiedNameString);
    if (!knownPackages.containsKey(qualifiedName)) {
      knownPackages.put(
          qualifiedName,
          new TreeBackedPackageElement(
              getSimpleName(qualifiedName),
              qualifiedName,
              javacElements.getPackageElement(qualifiedName),
              Preconditions.checkNotNull(resolverFactory)));
    }

    return Preconditions.checkNotNull(knownPackages.get(qualifiedName));
  }

  private Name getSimpleName(Name qualifiedName) {
    for (int i = qualifiedName.length() - 1; i >= 0; i--) {
      if (qualifiedName.charAt(i) == '.') {
        return javacElements.getName(qualifiedName.subSequence(i, qualifiedName.length()));
      }
    }

    return qualifiedName;
  }

  /**
   * Gets the type element with the given name. If a class with the given name is referenced in
   * the code or exists in the classpath, returns the corresponding element. Otherwise returns
   * null.
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

  /* package */ void enterTypeElement(TreeBackedTypeElement element) {
    Name name = element.getQualifiedName();

    if (knownTypes.containsKey(name)) {
      throw new AssertionError(String.format("Type collision for %s", name));
    }
    knownTypes.put(name, element);
  }

  @Override
  public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(
      AnnotationMirror a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getDocComment(Element e) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isDeprecated(Element e) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Name getBinaryName(TypeElement type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PackageElement getPackageOf(Element type) {
    throw new UnsupportedOperationException();
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
    throw new UnsupportedOperationException();
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
