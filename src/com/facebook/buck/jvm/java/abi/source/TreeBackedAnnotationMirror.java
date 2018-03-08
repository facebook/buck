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
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.type.DeclaredType;

class TreeBackedAnnotationMirror implements ArtificialAnnotationMirror {
  private final AnnotationMirror underlyingAnnotationMirror;
  private final TreePath treePath;
  private final AnnotationTree tree;
  private final PostEnterCanonicalizer canonicalizer;

  @Nullable private DeclaredType type;
  @Nullable private Map<ExecutableElement, TreeBackedAnnotationValue> elementValues;

  TreeBackedAnnotationMirror(
      AnnotationMirror underlyingAnnotationMirror,
      TreePath treePath,
      PostEnterCanonicalizer canonicalizer) {
    this.underlyingAnnotationMirror = underlyingAnnotationMirror;
    this.treePath = treePath;
    this.tree = (AnnotationTree) treePath.getLeaf();
    this.canonicalizer = canonicalizer;
  }

  /* package */ AnnotationMirror getUnderlyingAnnotationMirror() {
    return underlyingAnnotationMirror;
  }

  public TreePath getTreePath() {
    return treePath;
  }

  @Override
  public DeclaredType getAnnotationType() {
    if (type == null) {
      type =
          (DeclaredType)
              canonicalizer.getCanonicalType(
                  underlyingAnnotationMirror.getAnnotationType(),
                  treePath,
                  tree.getAnnotationType());
    }
    return type;
  }

  @Override
  public Map<ExecutableElement, TreeBackedAnnotationValue> getElementValues() {
    if (elementValues == null) {
      Map<ExecutableElement, TreeBackedAnnotationValue> result = new LinkedHashMap<>();
      Map<String, Tree> trees = new HashMap<>();

      List<? extends ExpressionTree> arguments = tree.getArguments();
      for (ExpressionTree argument : arguments) {
        if (argument.getKind() != Tree.Kind.ASSIGNMENT) {
          trees.put("value", argument);
        } else {
          AssignmentTree assignment = (AssignmentTree) argument;
          IdentifierTree nameTree = (IdentifierTree) assignment.getVariable();
          trees.put(nameTree.getName().toString(), argument);
        }
      }

      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
          underlyingAnnotationMirror.getElementValues().entrySet()) {
        ExecutableElement underlyingKeyElement = entry.getKey();

        Tree valueTree =
            Preconditions.checkNotNull(trees.get(entry.getKey().getSimpleName().toString()));

        result.put(
            canonicalizer.getCanonicalElement(underlyingKeyElement),
            new TreeBackedAnnotationValue(
                entry.getValue(), new TreePath(treePath, valueTree), canonicalizer));
      }

      elementValues = Collections.unmodifiableMap(result);
    }
    return elementValues;
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append("@");
    result.append(getAnnotationType());
    Map<ExecutableElement, TreeBackedAnnotationValue> elementValues = getElementValues();
    if (!elementValues.isEmpty()) {
      result.append("(");
      result.append(
          elementValues
              .entrySet()
              .stream()
              .map(
                  entry -> {
                    Name key = entry.getKey().getSimpleName();
                    TreeBackedAnnotationValue value = entry.getValue();
                    if (elementValues.size() == 1 && key.contentEquals("value")) {
                      return value.toString();
                    } else {
                      return String.format("%s=%s", key, value);
                    }
                  })
              .collect(Collectors.joining(", ")));
      result.append(")");
    }

    return result.toString();
  }
}
