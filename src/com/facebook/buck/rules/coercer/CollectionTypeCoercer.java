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

import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;

import java.nio.file.Path;
import java.util.Collection;

public abstract class CollectionTypeCoercer<C extends ImmutableCollection<T>, T>
    implements TypeCoercer<C> {
  private final TypeCoercer<T> elementTypeCoercer;

  CollectionTypeCoercer(TypeCoercer<T> elementTypeCoercer) {
    this.elementTypeCoercer = Preconditions.checkNotNull(elementTypeCoercer);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return elementTypeCoercer.hasElementClass(types);
  }

  @Override
  public boolean traverse(Object object, Traversal traversal) {
    if (object instanceof Collection) {
      traversal.traverse(object);
      for (Object element : (Iterable<?>) object) {
        elementTypeCoercer.traverse(element, traversal);
      }
      return true;
    } else {
      return false;
    }
  }

  /**
   * Helper method to add coerced elements to the builder.
   */
  protected void fill(
      BuildTargetParser buildTargetParser,
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      C.Builder<T> builder,
      Object object) throws CoerceFailedException {
    if (object instanceof Collection) {
      for (Object element : (Iterable<?>) object) {
        // if any element failed, the entire collection fails
        T coercedElement = elementTypeCoercer.coerce(
            buildTargetParser,
            buildRuleResolver,
            filesystem,
            pathRelativeToProjectRoot,
            element);
        builder.add(coercedElement);
      }
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }
}
