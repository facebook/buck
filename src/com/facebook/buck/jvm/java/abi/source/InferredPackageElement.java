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

package com.facebook.buck.jvm.java.abi.source;

import java.util.Collections;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;

/**
 * A {@link javax.lang.model.element.PackageElement} whose existence is inferred from references to
 * it in the code.
 */
class InferredPackageElement extends InferredElement implements ArtificialPackageElement {
  private final Name qualifiedName;
  private final TypeMirror typeMirror;

  public InferredPackageElement(Name simpleName, Name qualifiedName) {
    super(simpleName, null);
    this.qualifiedName = qualifiedName;
    typeMirror = new StandalonePackageType(this);
  }

  @Override
  public Set<Modifier> getModifiers() {
    // Packages have no modifiers, so we can implememnt this method here
    return Collections.emptySet();
  }

  @Override
  public Name getQualifiedName() {
    return qualifiedName;
  }

  @Override
  public boolean isUnnamed() {
    return qualifiedName.length() == 0;
  }

  @Override
  public TypeMirror asType() {
    return typeMirror;
  }

  @Override
  public ElementKind getKind() {
    return ElementKind.PACKAGE;
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitPackage(this, p);
  }

  @Override
  public String toString() {
    return qualifiedName.toString();
  }
}
