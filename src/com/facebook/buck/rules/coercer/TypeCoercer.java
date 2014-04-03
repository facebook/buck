/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;

import java.nio.file.Path;

/**
 * Class defining an interpretation of some dynamically typed Java object as a specific class.
 *
 * Used to coerce JSON parser output from BUCK files into the proper type to populate Description
 * rule args.
 *
 * @param <T> resulting type
 */
public interface TypeCoercer<T> {

  public Class<T> getOutputClass();

  /**
   * Returns whether the leaf nodes of this type coercer outputs value that is an instance of the
   * given class or its subclasses. Does not match non-leaf nodes like Map or List.
   */
  public boolean hasElementClass(Class<?>... types);

  /**
   * Traverse an object guided by this TypeCoercer.
   *
   * #{link Traversal#traverse} function will be called once for the object.
   * If the object is a collection or map, it will also recursively traverse all elements of the
   * map.
   *
   * Note that this does not check that the input can be coerced, except with respect to traversal.
   * That is, it will check that the object is a collection before traversing down its members, but
   * will not check that it's indeed a BuildTarget.
   *
   * @return Whether the traversal succeeded.
   */
  public boolean traverse(Object object, Traversal traversal);

  /**
   * @throws CoerceFailedException Input object cannot be coerced into the given type.
   */
  public T coerce(
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException;

  /**
   * Get a value suitable for an Optional field. This will typically be {@link Optional#absent()},
   * but may be some other value, such as an empty collection.
   *
   * @return A value suitable for fields of type Optional when no value has been set.
   */
  public Optional<T> getOptionalValue();

  public static interface Traversal {
    public void traverse(Object object);
  }
}
