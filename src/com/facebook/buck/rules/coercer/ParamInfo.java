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
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** Represents a single field that can be represented in buck build files. */
public interface ParamInfo<T> {

  /** @return the user-facing name of this parameter */
  String getName();

  /** @return the {@link TypeCoercer} that converts raw values to the correct type for this param */
  TypeCoercer<?, T> getTypeCoercer();

  /** @return Whether the coerced type is Optional or not */
  boolean isOptional();

  /** @return the python-friendly (snake case) name for this param */
  String getPythonName();

  /** @return Whether or not this parameter is a dependency */
  boolean isDep();

  /** @see Hint#isTargetGraphOnlyDep() */
  boolean isTargetGraphOnlyDep();

  /** @see Hint#isConfigurable() */
  boolean isInput();

  /** @return A hint about the type of this param */
  @Nullable
  Hint getHint();

  /** @return Whether this attribute is configurable or not */
  boolean isConfigurable();

  /** @see Hint#splitConfiguration() */
  boolean splitConfiguration();

  /** @see Hint#execConfiguration() */
  boolean execConfiguration();

  /**
   * @return the type that input values will be coerced to. Return the type parameter of Optional if
   *     wrapped in Optional.
   */
  Class<?> getResultClass();

  /**
   * Traverse the value of the field on {@code dto} that is represented by this instance.
   *
   * <p>If this field has a top level Optional type, traversal begins at the Optional value, or not
   * at all if the field is empty.
   *
   * @param cellPathResolver
   * @param traversal traversal to apply on the values.
   * @param dto the object whose field will be traversed.
   * @see TypeCoercer#traverse(com.facebook.buck.core.cell.nameresolver.CellNameResolver, Object,
   *     TypeCoercer.Traversal)
   */
  void traverse(CellNameResolver cellPathResolver, Traversal traversal, Object dto);

  /**
   * @return The value for this parameter if it is an "implicit" attribute, otherwise {@code null}
   *     <p>This is used for parameters that have a default value and need to be accessed by users'
   *     rule implementations, but should not be set directly by users. e.g. underscore prefixed
   *     attributes in user defined rules. These values are pre-coercion and may be user provided.
   *     If this parameter is not an implicit parameter, this method should return {@code null}
   */
  @Nullable
  Object getImplicitPreCoercionValue();

  /** @return the value of this param as set on dto. */
  T get(Object dto);

  /**
   * Sets a single property of the {@code dto}, coercing types as necessary.
   *
   * @param cellNameResolver
   * @param filesystem {@link ProjectFilesystem} used to ensure {@link Path}s exist.
   * @param pathRelativeToProjectRoot The path relative to the project root that this DTO is for.
   * @param hostConfiguration
   * @param dto The constructor DTO on which the value should be set.
   * @param value The value, which may be coerced depending on the type on {@code dto}.
   */
  void set(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object dto,
      @Nullable Object value)
      throws ParamInfoException;

  boolean hasElementTypes(Class<?>... types);

  /**
   * Set the param on dto to value, assuming value has already been coerced.
   *
   * <p>This is useful for things like making copies of dtos.
   */
  void setCoercedValue(Object dto, Object value);

  /** Traversal interface used when coercing values */
  interface Traversal extends TypeCoercer.Traversal {}
}
