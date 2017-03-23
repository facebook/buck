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

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;

/**
 * Used to resolve type references in {@link TreeBackedElement}s after they've all been created.
 */
class TreeBackedElementResolver {
  private final TreeBackedElements elements;
  private final TreeBackedTypes types;

  public TreeBackedElementResolver(TreeBackedElements elements, TreeBackedTypes types) {
    this.elements = elements;
    this.types = types;
  }

  /* package */ StandaloneDeclaredType createType(TreeBackedTypeElement element) {
    return new StandaloneDeclaredType(
        types,
        element,
        element.getTypeParameters().stream()
            .map(TypeParameterElement::asType)
            .collect(Collectors.toList()));
  }

  /* package */ StandaloneTypeVariable createType(TreeBackedTypeParameterElement element) {
    return new StandaloneTypeVariable(types, element);
  }

  /* package */ StandalonePackageType createType(TreeBackedPackageElement element) {
    return new StandalonePackageType(element);
  }

  public ExecutableElement getCanonicalElement(ExecutableElement element) {
    return elements.getCanonicalElement(element);
  }

  /* package */ TypeMirror getCanonicalType(TypeMirror javacType) {
    return types.getCanonicalType(javacType);
  }

  /**
   * Canonicalizes any {@link javax.lang.model.element.Element}s, {@link TypeMirror}s, or
   * {@link AnnotationValue}s found in the given object, which is expected to have been obtained
   * by calling {@link AnnotationValue#getValue()}.
   */
  /* package */ Object getCanonicalValue(AnnotationValue annotationValue, TreePath path) {
    return annotationValue.accept(new SimpleAnnotationValueVisitor8<Object, Void>() {
      @Override
      public Object visitType(TypeMirror t, Void aVoid) {
        return types.getCanonicalType(t);
      }

      @Override
      public Object visitEnumConstant(VariableElement c, Void aVoid) {
        return elements.getCanonicalElement(c);
      }

      @Override
      public Object visitAnnotation(AnnotationMirror a, Void aVoid) {
        return new TreeBackedAnnotationMirror(a, path, TreeBackedElementResolver.this);
      }

      @Override
      public Object visitArray(List<? extends AnnotationValue> values, Void aVoid) {
        Tree valueTree = path.getLeaf();

        if (valueTree instanceof NewArrayTree) {
          NewArrayTree tree = (NewArrayTree) path.getLeaf();
          List<? extends ExpressionTree> valueTrees = tree.getInitializers();

          List<TreeBackedAnnotationValue> result = new ArrayList<>();
          for (int i = 0; i < values.size(); i++) {
            result.add(new TreeBackedAnnotationValue(
                values.get(i),
                new TreePath(path, valueTrees.get(i)),
                TreeBackedElementResolver.this));
          }
          return result;
        } else {
          return Collections.singletonList(new TreeBackedAnnotationValue(
              values.get(0),
              path,
              TreeBackedElementResolver.this));
        }
      }

      @Override
      protected Object defaultAction(Object o, Void aVoid) {
        // Everything else (primitives, Strings, enums) doesn't need canonicalization
        return o;
      }
    }, null);
  }
}
