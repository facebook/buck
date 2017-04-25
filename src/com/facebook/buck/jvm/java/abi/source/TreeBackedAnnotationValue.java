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

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.List;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;

class TreeBackedAnnotationValue implements AnnotationValue {
  private final AnnotationValue underlyingAnnotationValue;
  private final TreePath valuePath;
  private final TreeBackedElementResolver resolver;

  @Nullable private Object value;

  TreeBackedAnnotationValue(
      AnnotationValue underlyingAnnotationValue,
      TreePath path,
      TreeBackedElementResolver resolver) {
    this.underlyingAnnotationValue = underlyingAnnotationValue;
    Tree leaf = path.getLeaf();
    if (leaf instanceof AssignmentTree) {
      AssignmentTree assignmentTree = (AssignmentTree) leaf;
      valuePath = new TreePath(path, assignmentTree.getExpression());
    } else {
      valuePath = path;
    }
    this.resolver = resolver;
  }

  @Override
  public Object getValue() {
    if (value == null) {
      value = resolver.getCanonicalValue(underlyingAnnotationValue, valuePath);
    }
    return value;
  }

  @Override
  public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
    Object underlyingValue = underlyingAnnotationValue.getValue();
    Object value = getValue();

    if (underlyingValue.equals(value)) {
      return underlyingAnnotationValue.accept(v, p);
    }

    if (value instanceof TreeBackedVariableElement) {
      return v.visitEnumConstant((TreeBackedVariableElement) value, p);
    } else if (value instanceof StandaloneTypeMirror) {
      return v.visitType((StandaloneTypeMirror) value, p);
    } else if (value instanceof TreeBackedAnnotationMirror) {
      return v.visitAnnotation((TreeBackedAnnotationMirror) value, p);
    } else if (value instanceof List) {
      @SuppressWarnings("unchecked")
      List<? extends AnnotationValue> valuesList = (List<? extends AnnotationValue>) value;
      return v.visitArray(valuesList, p);
    } else {
      throw new IllegalStateException(String.format("Unexpected annotation value: %s", value));
    }
  }
}
