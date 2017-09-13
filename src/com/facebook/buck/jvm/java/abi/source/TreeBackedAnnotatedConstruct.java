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

package com.facebook.buck.jvm.java.abi.source;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.AnnotatedConstruct;

/**
 * An implementation of {@link javax.lang.model.AnnotatedConstruct} that uses only the information
 * available from a {@link com.sun.source.tree.Tree}. This results in an incomplete implementation;
 * see documentation for individual methods and {@link com.facebook.buck.jvm.java.abi.source} for
 * more information.
 */
public class TreeBackedAnnotatedConstruct implements ArtificialAnnotatedConstruct {
  private final AnnotatedConstruct underlyingConstruct;
  private final List<TreeBackedAnnotationMirror> annotationMirrors = new ArrayList<>();

  public TreeBackedAnnotatedConstruct(AnnotatedConstruct underlyingConstruct) {
    this.underlyingConstruct = underlyingConstruct;
  }

  /* package */ void addAnnotationMirror(TreeBackedAnnotationMirror annotationMirror) {
    annotationMirrors.add(annotationMirror);
  }

  @Override
  public List<? extends ArtificialAnnotationMirror> getAnnotationMirrors() {
    return Collections.unmodifiableList(annotationMirrors);
  }

  @Override
  public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    return underlyingConstruct.getAnnotation(annotationType);
  }

  @Override
  public <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    return underlyingConstruct.getAnnotationsByType(annotationType);
  }
}
