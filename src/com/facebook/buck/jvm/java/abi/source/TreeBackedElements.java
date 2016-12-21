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
  private final Map<Name, TreeBackedTypeElement> types = new HashMap<>();

  public TreeBackedElements(Elements javacElements) {
    this.javacElements = javacElements;
  }

  @Override
  public PackageElement getPackageElement(CharSequence name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TreeBackedTypeElement getTypeElement(CharSequence nameString) {
    Name qualifiedName = getName(nameString);
    if (!types.containsKey(qualifiedName)) {
      // Because we don't have access to the current target's dependencies, we have to assume that
      // any type name we don't know about exists in one of those. Whatever our caller does with
      // this information will need to be validated later against the full set of dependencies.
      throw new UnsupportedOperationException(
          "TODO: Need to create placeholders for unknown type names.");
    }

    return types.get(qualifiedName);
  }

  /* package */ void enterTypeElement(TreeBackedTypeElement element) {
    Name name = element.getQualifiedName();

    if (types.containsKey(name)) {
      throw new AssertionError(String.format("Type collision for %s", name));
    }
    types.put(name, element);
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
