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

import java.nio.file.Path;

import javax.annotation.Nullable;

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
   * For primitive types, return the output class.
   *
   * For collection types, return the recursive element class. (i.e. for a collection of
   * collections, return the leaf class of the collection).
   */
  public Class<?> getLeafClass();

  /**
   * Returns the key class of the outermost map.
   *
   * @return the key class if the coerced type is a map, otherwise return null.
   */
  @Nullable
  public Class<?> getKeyClass();

  /**
   * Traverse an object guided by this TypeCoercer.
   *
   * #{link Traversal#traverse} function will be called once for the object.
   * If the object is a collection or map, it will also recursively traverse all elements of the
   * map.
   */
  public void traverse(Object object, Traversal traversal);

  /**
   * @throws CoerceFailedException Input object cannot be coerced into the given type.
   */
  public T coerce(
      BuildRuleResolver buildRuleResolver,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException;

  public static interface Traversal {
    public void traverse(Object object);
  }
}
