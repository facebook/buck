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

import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;

/**
 * After the enter phase is complete, this class can obtain the "canonical" version of any {@link
 * javax.lang.model.element.Element}, {@link TypeMirror}, or {@link AnnotationValue}. The canonical
 * versions of these are the artificial ones if they exist, otherwise the javac one.
 */
class PostEnterCanonicalizer {
  private final TreeBackedElements elements;
  private final TreeBackedTypes types;

  public PostEnterCanonicalizer(TreeBackedElements elements, TreeBackedTypes types) {
    this.elements = elements;
    this.types = types;
  }

  public ExecutableElement getCanonicalElement(ExecutableElement element) {
    return Preconditions.checkNotNull(elements.getCanonicalElement(element));
  }

  /* package */ TypeMirror getCanonicalType(TypeMirror javacType) {
    return Preconditions.checkNotNull(types.getCanonicalType(javacType));
  }

  /**
   * Canonicalizes any {@link javax.lang.model.element.Element}s, {@link TypeMirror}s, or {@link
   * AnnotationValue}s found in the given object, which is expected to have been obtained by calling
   * {@link AnnotationValue#getValue()}.
   */
  /* package */ Object getCanonicalValue(AnnotationValue annotationValue, TreePath valueTreePath) {
    return annotationValue.accept(
        new SimpleAnnotationValueVisitor8<Object, Void>() {
          @Override
          public Object visitType(TypeMirror t, Void aVoid) {
            return getCanonicalType(t);
          }

          @Override
          public Object visitEnumConstant(VariableElement c, Void aVoid) {
            return Preconditions.checkNotNull(elements.getCanonicalElement(c));
          }

          @Override
          public Object visitAnnotation(AnnotationMirror a, Void aVoid) {
            return new TreeBackedAnnotationMirror(a, valueTreePath, PostEnterCanonicalizer.this);
          }

          @Override
          public Object visitArray(List<? extends AnnotationValue> values, Void aVoid) {
            Tree valueTree = valueTreePath.getLeaf();
            if (valueTree instanceof NewArrayTree) {
              NewArrayTree tree = (NewArrayTree) valueTree;
              List<? extends ExpressionTree> valueTrees = tree.getInitializers();

              List<TreeBackedAnnotationValue> result = new ArrayList<>();
              for (int i = 0; i < values.size(); i++) {
                result.add(
                    new TreeBackedAnnotationValue(
                        values.get(i),
                        new TreePath(valueTreePath, valueTrees.get(i)),
                        PostEnterCanonicalizer.this));
              }
              return result;
            } else {
              return Collections.singletonList(
                  new TreeBackedAnnotationValue(
                      values.get(0),
                      new TreePath(valueTreePath, valueTree),
                      PostEnterCanonicalizer.this));
            }
          }

          @Override
          protected Object defaultAction(Object o, Void aVoid) {
            // Everything else (primitives, Strings, enums) doesn't need canonicalization
            return o;
          }
        },
        null);
  }
}
