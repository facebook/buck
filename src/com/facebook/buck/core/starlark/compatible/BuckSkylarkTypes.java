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

package com.facebook.buck.core.starlark.compatible;

import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;

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
   * <pre>{@code
   * StarlarkList<?> skyList = // Provided by user
   * // Works, but compiler complains because Provider is not specialized
   * ImmutableList<Provider> providers = skyList.getContents(Provider.class, null));
   * // Does not work because Provider.class != the class of Provider<?>
   * ImmutableList<Provider<?>> providers = skyList.getContents(Provider.class, null));
   * }</pre>
   *
   * This function makes the following usage possible
   *
   * <pre>{@code
   * StarlarkList<?> skyList = // Provided by user
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
          Sequence<?> list, Class<NonWildcardType> elementClass, String description)
          throws EvalException {
    for (Object o : list) {
      if (!(elementClass.isInstance(o))) {
        throw new EvalException(
            String.format(
                "%s: expected a sequence of '%s'", description, elementClass.getSimpleName()));
      }
    }

    return ImmutableList.copyOf((Sequence<DestType>) list);
  }

  /** Runtime check dict keys and values. */
  @SuppressWarnings("unchecked")
  public static <K, V> ImmutableMap<K, V> toJavaMap(
      Dict<?, ?> dict, Class<K> keyClass, Class<V> valueClass, String description)
      throws EvalException {
    for (Map.Entry<?, ?> entry : dict.entrySet()) {
      if (!keyClass.isInstance(entry.getKey())) {
        throw new EvalException(description);
      }
      if (!valueClass.isInstance(entry.getValue())) {
        throw new EvalException(description);
      }
    }

    return ImmutableMap.copyOf((Dict<K, V>) dict);
  }

  /**
   * Ensure that a name is a valid kwarg/field identifier
   *
   * <p>This is useful as Skylark does not validate these by default when creating a signature
   *
   * @param kwarg the name of a kwarg / field
   */
  public static ParamName validateKwargName(String kwarg) throws EvalException {
    /**
     * Identifier regex is taken from Bazel's skylark {@link Lexer} line 884 (where @{link
     * Lexer#identifierOrKeyword} is called.
     *
     * <p>Bazel/skylark does not do this validation by default, and allows kwargs to be created that
     * cannot be used any other way than calling this class with <code>**kwargs</code>, and
     * accessing the parameter with <code>getattr(ctx.attr, "some attr")</code> We'd rather just
     * bail out early so typos don't confuse users or allow non-idiomatic usage
     */
    try {
      return ParamName.bySnakeCase(kwarg);
    } catch (IllegalArgumentException e) {
      throw new EvalException(
          String.format("Attribute name '%s' is not a valid identifier", kwarg));
    }
  }

  /**
   * @return {@code None} if {@code object} is {@code null}, else return {@code object}. This is
   *     just a central place ot make sure things we return from internal implementations into
   *     Skylark are properly turned into None
   */
  public static Object skylarkValueFromNullable(@Nullable Object object) {
    return object == null ? Starlark.NONE : object;
  }

  /**
   * Check that a value is either {@link Starlark#NONE} or an instance of {@code clazz}
   *
   * @param clazz the class that the object should be an instance of if not {@link Starlark#NONE}
   * @param object the object to check
   * @return the original value if it is of a correct type
   * @throws EvalException if the object is not of the correct type
   */
  public static Object validateNoneOrType(Class<?> clazz, Object object) throws EvalException {
    if (object == Starlark.NONE || clazz.isAssignableFrom(object.getClass())) {
      return object;
    }
    throw new EvalException(
        String.format(
            "Invalid type provided. Expected %s, got %s",
            clazz.getSimpleName(), object.getClass().getSimpleName()));
  }

  /**
   * Checks if a given value is 'Immutable'. This mostly works like {@link
   * Starlark#isImmutable(java.lang.Object)}, but it can also handle {@link
   * com.google.common.collect.ImmutableCollection} and {@link
   * com.google.common.collect.ImmutableMap}
   */
  public static boolean isImmutable(Object o) {
    if (o instanceof ImmutableCollection<?>) {
      return ((ImmutableCollection<?>) o).stream().allMatch(BuckSkylarkTypes::isImmutable);
    } else if (o instanceof ImmutableMap<?, ?>) {
      return ((ImmutableMap<?, ?>) o).values().stream().allMatch(BuckSkylarkTypes::isImmutable);
    } else {
      return Starlark.isImmutable(o);
    }
  }
}
