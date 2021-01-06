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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.concat.Concatable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import javax.annotation.Nullable;

/**
 * Class defining an interpretation of some dynamically typed Java object as a specific class.
 *
 * <p>Used to coerce JSON parser output from BUCK files into the proper type to populate Description
 * rule args.
 *
 * @param <T> resulting type
 * @param <U> input type
 */
public interface TypeCoercer<U, T> extends Concatable<T> {

  TypeToken<T> getOutputType();

  TypeToken<U> getUnconfiguredType();

  /**
   * {@link #coerce(CellNameResolver, ProjectFilesystem, ForwardRelativePath, TargetConfiguration,
   * TargetConfiguration, Object)} must be no-op when this returns {@code true}.
   */
  default boolean unconfiguredToConfiguredCoercionIsIdentity() {
    return false;
  }

  /**
   * Returns whether the leaf nodes of this type coercer outputs value that is an instance of the
   * given class or its subclasses. Does not match non-leaf nodes like Map or List.
   */
  boolean hasElementClass(Class<?>... types);

  /**
   * Traverse an object guided by this TypeCoercer.
   *
   * <p>#{link Traversal#traverse} function will be called once for the object. If the object is a
   * collection or map, it will also recursively traverse all elements of the map.
   */
  void traverse(CellNameResolver cellRoots, T object, Traversal traversal);

  /** Coerce to a value for unconfigured graph. */
  U coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException;

  /** @throws CoerceFailedException Input object cannot be coerced into the given type. */
  T coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      U object)
      throws CoerceFailedException;

  /**
   * Apply {@link #coerceToUnconfigured(CellNameResolver, ProjectFilesystem, ForwardRelativePath,
   * Object)} followed by {@link #coerce(CellNameResolver, ProjectFilesystem, ForwardRelativePath,
   * TargetConfiguration, TargetConfiguration, Object)}.
   */
  default T coerceBoth(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    U unconfigured = coerceToUnconfigured(cellRoots, filesystem, pathRelativeToProjectRoot, object);
    return coerce(
        cellRoots,
        filesystem,
        pathRelativeToProjectRoot,
        targetConfiguration,
        hostConfiguration,
        unconfigured);
  }

  /**
   * Implementation of concatenation for this type. <code>null</code> indicates that concatenation
   * isn't supported by the type.
   */
  @Nullable
  @Override
  default T concat(Iterable<T> elements) {
    return null;
  }

  /** @return {@code true} is this coercer supports concatenation. */
  default boolean supportsConcatenation() {
    return concat(ImmutableList.of()) != null;
  }

  interface Traversal {
    void traverse(Object object);
  }

  /** Runtime checked cast. */
  @SuppressWarnings("unchecked")
  default <S> TypeCoercer<U, S> checkOutputAssignableTo(TypeToken<S> type) {
    Preconditions.checkState(
        this.getOutputType().wrap().isSubtypeOf(type.wrap()),
        "actual output type %s must be a assignable to %s",
        this.getOutputType(),
        type);
    return (TypeCoercer<U, S>) this;
  }

  /** Runtime checked cast. */
  @SuppressWarnings("unchecked")
  default <S> TypeCoercer<S, T> checkUnconfiguredAssignableTo(TypeToken<S> type) {
    Preconditions.checkState(
        this.getUnconfiguredType().wrap().isSubtypeOf(type.wrap()),
        "actual unconfigured type %s must be a assignable to %s",
        this.getUnconfiguredType(),
        type);
    return (TypeCoercer<S, T>) this;
  }
}
