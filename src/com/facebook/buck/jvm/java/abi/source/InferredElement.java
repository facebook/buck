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

import com.facebook.buck.jvm.java.abi.source.api.CannotInferException;
import com.facebook.buck.util.liteinfersupport.Nullable;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;

/**
 * Represents an {@link javax.lang.model.element.Element} whose existence has been inferred from
 * references to it in the code.
 */
abstract class InferredElement implements ArtificialElement {
  private final Name simpleName;
  @Nullable private final ArtificialElement enclosingElement;

  protected InferredElement(Name simpleName, @Nullable ArtificialElement enclosingElement) {
    this.simpleName = simpleName;
    this.enclosingElement = enclosingElement;
  }

  @Override
  public void addEnclosedElement(Element element) {
    // getEnclosedElements throws, so don't bother keeping track of them.
  }

  @Override
  public Set<Modifier> getModifiers() {
    throw new CannotInferException("modifiers", this);
  }

  @Override
  public final Name getSimpleName() {
    return simpleName;
  }

  @Override
  @Nullable
  public ArtificialElement getEnclosingElement() {
    return enclosingElement;
  }

  @Override
  public List<? extends Element> getEnclosedElements() {
    throw new CannotInferException("enclosed elements", this);
  }

  @Override
  public final List<? extends AnnotationMirror> getAnnotationMirrors() {
    throw new CannotInferException("annotations", this);
  }

  @Override
  public final <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    throw new CannotInferException("annotations", this);
  }

  @Override
  public final <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    throw new CannotInferException("annotations", this);
  }
}
