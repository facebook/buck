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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultTargetNode;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.function.Function;

public class TargetNodeTypeCoercer extends TypeCoercer<TargetNode<?, ?>> {

  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;
  private final Class<?> targetNodeType;
  private final Class<?> constructorArgType;
  private final Class<?> descriptionType;

  TargetNodeTypeCoercer(
      TypeCoercer<BuildTarget> buildTargetTypeCoercer,
      Class<?> targetNodeType,
      Class<?> constructorArgType,
      Class<?> descriptionType) {
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.targetNodeType = targetNodeType;
    this.constructorArgType = constructorArgType;
    this.descriptionType = descriptionType;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Class<TargetNode<?, ?>> getOutputClass() {
    return (Class<TargetNode<?, ?>>) (Class<?>) TargetNode.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    for (Class<?> type : types) {
      if (type.isAssignableFrom(getOutputClass())) {
        return true;
      }
    }
    return buildTargetTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(TargetNode<?, ?> object, Traversal traversal) {
    traversal.traverse(object);
    traversal.traverse(object.getBuildTarget());
  }

  @Override
  public TargetNode<?, ?> coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    return coerceHelper(cellRoots, filesystem, pathRelativeToProjectRoot, object);
  }

  @SuppressWarnings("unchecked")
  private <T, U extends Description<T>> TargetNode<T, U> coerceHelper(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    return new TargetNodeStub<T, U>(
        buildTargetTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, object),
        (Class<T>) constructorArgType,
        (Class<U>) descriptionType);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <U> TargetNode<?, ?> mapAll(
      Function<U, U> function,
      Class<U> targetClass,
      Object object) throws CoerceFailedException {
    if (TargetNode.class.isAssignableFrom(targetClass)) {
      TargetNode<?, ?> originalTargetNode = (TargetNode<?, ?>) object;
      TargetNode<?, ?> targetNode = (TargetNode<?, ?>) function.apply((U) object);
      if (object instanceof TargetNodeStub) {
        TargetNodeStub<?, ?> targetNodeReference = (TargetNodeStub<?, ?>) object;
        boolean descriptionMatches = targetNodeReference
            .getDescriptionType()
            .isAssignableFrom(targetNode.getDescription().getClass());
        boolean constructorArgMatches = targetNodeReference
            .getConstructorArgType()
            .isAssignableFrom(targetNode.getConstructorArg().getClass());
        if (!descriptionMatches || !constructorArgMatches) {
          Class<?> expectedType = targetNodeReference.getDescriptionType();
          if (expectedType.isInterface() || Modifier.isAbstract(expectedType.getModifiers())) {
            throw new UnresolvedDescriptionConstraintCoerceFailedException(
                targetNodeReference.getBuildTarget(),
                expectedType,
                targetNode.getDescription());
          } else {
            throw new CoerceFailedException(
                String.format(
                    "Unexpected target type: expected '%s' to be %s, was '%s'.",
                    targetNodeReference.getBuildTarget(),
                    "'" +
                        Description.getBuildRuleType(targetNodeReference.getDescriptionType()) +
                        "'",
                    Description.getBuildRuleType(targetNode.getDescription())));
          }
        }
      } else {
        boolean descriptionMatches = originalTargetNode
            .getDescription()
            .getClass()
            .isAssignableFrom(targetNode.getDescription().getClass());
        boolean constructorArgMatches = originalTargetNode
            .getConstructorArg()
            .getClass()
            .isAssignableFrom(targetNode.getConstructorArg().getClass());
        if (!descriptionMatches || !constructorArgMatches) {
          throw new CoerceFailedException(
              String.format(
                  "Unexpected target type: expected '%s' to be %s, was '%s'.",
                  originalTargetNode.getBuildTarget(),
                  "'" + Description.getBuildRuleType(originalTargetNode.getDescription()) + "'",
                  Description.getBuildRuleType(targetNode.getDescription())));
        }
      }
      if (!targetNodeType.equals(TargetNode.class)) {
        targetNode = wrapTargetNode(targetNode);
      }
      return targetNode;
    }
    return mapAllInternal(function, targetClass, (TargetNode<?, ?>) object);
  }

  @Override
  public <U> TargetNode<?, ?> mapAllInternal(
      Function<U, U> function,
      Class<U> targetClass,
      TargetNode<?, ?> object) throws CoerceFailedException {
    return mapAllHelper(function, targetClass, object);
  }

  @SuppressWarnings("unchecked")
  private <T, U extends Description<T>, V> TargetNode<T, U> mapAllHelper(
      Function<V, V> function,
      Class<V> targetClass,
      TargetNode<T, U> object) throws CoerceFailedException {
    return new TargetNodeStub<T, U> (
        buildTargetTypeCoercer.mapAll(function, targetClass, object.getBuildTarget()),
        (Class<T>) constructorArgType,
        (Class<U>) descriptionType);
  }

  @SuppressWarnings("unchecked")
  private <T, U extends Description<T>> TargetNode<T, U> wrapTargetNode(TargetNode<T, U> node) {
    try {
      return (TargetNode<T, U>) targetNodeType
          .getConstructor(DefaultTargetNode.class)
          .newInstance(node);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }
}


