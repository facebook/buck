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

package com.facebook.buck.jvm.java.plugin.adapter;

import com.facebook.buck.util.liteinfersupport.Nullable;
import java.io.Writer;
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

/** Delegates all method calls to an inner instance of {@link Elements}. */
class DelegatingElements implements Elements {
  private final Elements inner;

  public DelegatingElements(Elements inner) {
    this.inner = inner;
  }

  @Override
  @Nullable
  public PackageElement getPackageElement(CharSequence name) {
    return inner.getPackageElement(name);
  }

  @Override
  @Nullable
  public TypeElement getTypeElement(CharSequence name) {
    return inner.getTypeElement(name);
  }

  @Override
  public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(
      AnnotationMirror a) {
    return inner.getElementValuesWithDefaults(a);
  }

  @Override
  @Nullable
  public String getDocComment(Element e) {
    return inner.getDocComment(e);
  }

  @Override
  public boolean isDeprecated(Element e) {
    return inner.isDeprecated(e);
  }

  @Override
  public Name getBinaryName(TypeElement type) {
    return inner.getBinaryName(type);
  }

  @Override
  public PackageElement getPackageOf(Element type) {
    return inner.getPackageOf(type);
  }

  @Override
  public List<? extends Element> getAllMembers(TypeElement type) {
    return inner.getAllMembers(type);
  }

  @Override
  public List<? extends AnnotationMirror> getAllAnnotationMirrors(Element e) {
    return inner.getAllAnnotationMirrors(e);
  }

  @Override
  public boolean hides(Element hider, Element hidden) {
    return inner.hides(hider, hidden);
  }

  @Override
  public boolean overrides(
      ExecutableElement overrider, ExecutableElement overridden, TypeElement type) {
    return inner.overrides(overrider, overridden, type);
  }

  @Override
  public String getConstantExpression(Object value) {
    return inner.getConstantExpression(value);
  }

  @Override
  public void printElements(Writer w, Element... elements) {
    inner.printElements(w, elements);
  }

  @Override
  public Name getName(CharSequence cs) {
    return inner.getName(cs);
  }

  @Override
  public boolean isFunctionalInterface(TypeElement type) {
    return inner.isFunctionalInterface(type);
  }
}
