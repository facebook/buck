/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.compatible;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Container class for helper methods having to deal with Skylark types. e.g. list or map conversion
 * utilities that supplement the built in helpers.
 */
public class BuckSkylarkTypes {
  private BuckSkylarkTypes() {}

  /**
   * Validate that all objects are of a given type and return an {@link ImmutableList} containing
   * those objects.
   *
   * <p>This is a workaround for {@link SkylarkList#getContents(Class, String)} being unable to
   * handle generic types correctly. e.g. it is currently impossible to do:
   *
   * <pre>{@code
   * SkylarkList<?> skyList = // Provided by user
   * // Works, but compiler complains because Provider is not specialized
   * ImmutableList<Provider> providers = skyList.getContents(Provider.class, null));
   * // Does not work because Provider.class != the class of Provider<?>
   * ImmutableList<Provider<?>> providers = skyList.getContents(Provider.class, null));
   * }</pre>
   *
   * This function makes the following usage possible
   *
   * <pre>{@code
   * SkylarkList<?> skyList = // Provided by user
   * ImmutableList<Provider<?>> providers = BuckSkylarkTypes.toJavaList(skyList, Provider.class, null));
   * }</pre>
   *
   * This function wraps a dirty type cast up and tries to apply some basic type boundaries to
   * reduce the likelihood of creating a foot gun.
   *
   * @param list The list of objects to validate
   * @param elementClass The non-wildcard version of the desired final class
   * @param description a description of the argument being converted, or null, for debugging
   * @param <NonWildcardType> The non-wildcard type. e.g. {@code Provider.class}
   * @param <DestType> The type of the elements in the resulting container. This can be either the
   *     same as {@code NonWildcardType}, or a generic type like {@code Provider<?>}
   * @return A list of {@code DestType}. This is immutable because we do some type casting under the
   *     hood, and do not want any assumptions to be made about the backing store of the container,
   *     nor do we want users to assume they can safely insert into that list.
   * @throws EvalException If one of the elements was not of the type specified in {@code
   *     elementClass}
   */
  @SuppressWarnings("unchecked")
  public static <NonWildcardType, DestType extends NonWildcardType>
      ImmutableList<DestType> toJavaList(
          SkylarkList<?> list, Class<NonWildcardType> elementClass, @Nullable String description)
          throws EvalException {
    return ImmutableList.copyOf((List<DestType>) list.getContents(elementClass, description));
  }
}
