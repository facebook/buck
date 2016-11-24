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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.CellPathResolver;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Class defining an interpretation of some dynamically typed Java object as a specific class.
 *
 * Used to coerce JSON parser output from BUCK files into the proper type to populate Description
 * rule args.
 *
 * @param <T> resulting type
 */
public abstract class TypeCoercer<T> {

  public abstract Class<T> getOutputClass();

  /**
   * Returns whether the leaf nodes of this type coercer outputs value that is an instance of the
   * given class or its subclasses. Does not match non-leaf nodes like Map or List.
   */
  public abstract boolean hasElementClass(Class<?>... types);

  /**
   * Traverse an object guided by this TypeCoercer.
   *
   * #{link Traversal#traverse} function will be called once for the object.
   * If the object is a collection or map, it will also recursively traverse all elements of the
   * map.
   */
  public abstract void traverse(T object, Traversal traversal);

  /**
   * @throws CoerceFailedException Input object cannot be coerced into the given type.
   */
  public abstract T coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException;

  @SuppressWarnings("unchecked")
  public <U> T mapAll(Function<U, U> function, Class<U> targetClass, Object object)
      throws CoerceFailedException {
    if (getOutputClass().isAssignableFrom(targetClass)) {
      return (T) function.apply((U) object);
    }
    return mapAllInternal(function, targetClass, (T) object);
  }

  protected abstract <U> T mapAllInternal(Function<U, U> function, Class<U> targetClass, T object)
      throws CoerceFailedException;

  public interface Traversal {
    void traverse(Object object);
  }
}
