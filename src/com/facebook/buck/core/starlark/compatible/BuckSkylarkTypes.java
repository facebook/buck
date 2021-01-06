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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Container class for helper methods having to deal with Skylark types. e.g. list or map conversion
 * utilities that supplement the built in helpers.
 */
public class BuckSkylarkTypes {

  /** Matches python identifiers in the parser */
  private static final Pattern VALID_IDENTIFIER_REGEX = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

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

  /**
   * Attempt to get a deeply immutable instance of a value passed in from Skylark
   *
   * <p>Note that if mutable objects are passed in (namely {@link SkylarkList} or {@link
   * SkylarkDict}, a copy may be made to get an immutable instance. This may happen in very deeply
   * nested structures, so the runtime is variable based on how mutable the objects are. For the
   * best performance, only immutable structures should be passed in, as that turns into a simple
   * identity function.
   *
   * @param arg A value from the skylark interpreter. This should only be primitive objects, or
   *     {@link com.google.devtools.build.lib.skylarkinterface.SkylarkValue} instances
   * @return
   *     <li>The original object if it was already an immutable {@link
   *         com.google.devtools.build.lib.skylarkinterface.SkylarkValue} or a primitive value
   *     <li>an immutable {@link SkylarkList} if the original object is a {@link SkylarkList} and
   *         all values were immutable or could be made immutable. As above, this may be a copy, or
   *         inner elements may have had to be copied if they were mutable
   *     <li>An immutable {@link SkylarkDict} if the original object is a {@link SkylarkDict} and
   *         all keys and values were immutable, or could be made so. Again, note that copies may be
   *         made in order to make mutable objects immutable
   * @throws MutableObjectException If a nested or top level object was mutable, and could not be
   *     made immutable. This generally only applies to incorrectly implemented native data types
   *     that are exported to Skylark.
   */
  public static Object asDeepImmutable(Object arg) throws MutableObjectException {
    // NOTE: For most built in types, this should be reliable. However, if isImmutable is improperly
    // implemented on our custom types (that is, it returns "true" when a sub-object is not actually
    // immutable), this has the potential to allow an immutable SkylarkList/Dict that contains
    // mutable objects. We would rather not pay to iterate and double check for immutability here,
    // so for now, just assume things are implemented correctly. The number of user-accessible
    // custom objects should be vanishingly small.

    // Grab frozen objects and primitives
    if (EvalUtils.isImmutable(arg)) {
      return arg;
    }

    if (arg instanceof SkylarkList) {
      SkylarkList<?> listArg = ((SkylarkList<?>) arg);
      return listArg.stream()
          .filter(a -> !EvalUtils.isImmutable(a))
          .findFirst()
          // if we have a mutable element, like a sub list, try to make it immutable
          .map(
              mutableElement ->
                  SkylarkList.createImmutable(
                      Iterables.transform(listArg, element -> asDeepImmutable(element))))
          // Else just copy the list elements into a list with an immutable mutability
          // We can't just freeze the list, as it may be mutated elsewhere, but this at least
          // elides a copy.
          .orElseGet(() -> SkylarkList.createImmutable(listArg));
    } else if (arg instanceof SkylarkDict) {
      SkylarkDict<?, ?> dictArg = (SkylarkDict<?, ?>) arg;
      ImmutableMap.Builder<Object, Object> tempBuilder =
          ImmutableMap.builderWithExpectedSize(dictArg.size());
      for (Map.Entry<?, ?> entry : dictArg.entrySet()) {
        tempBuilder.put(asDeepImmutable(entry.getKey()), asDeepImmutable(entry.getValue()));
      }
      return SkylarkDict.copyOf(null, tempBuilder.build());
    } else {
      throw new MutableObjectException(arg);
    }
  }

  /**
   * Ensure that a name is a valid kwarg/field identifier
   *
   * <p>This is useful as Skylark does not validate these by default when creating a signature
   *
   * @param location location of the kwarg currently being evaluated
   * @param kwarg the name of a kwarg / field
   */
  public static void validateKwargName(Location location, String kwarg) throws EvalException {
    /**
     * Identifier regex is taken from Bazel's skylark {@link Lexer} line 884 (where @{link
     * Lexer#identifierOrKeyword} is called.
     *
     * <p>Bazel/skylark does not do this validation by default, and allows kwargs to be created that
     * cannot be used any other way than calling this class with <code>**kwargs</code>, and
     * accessing the parameter with <code>getattr(ctx.attr, "some attr")</code> We'd rather just
     * bail out early so typos don't confuse users or allow non-idiomatic usage
     */
    if (!VALID_IDENTIFIER_REGEX.matcher(kwarg).matches()) {
      throw new EvalException(
          location, String.format("Attribute name '%s' is not a valid identifier", kwarg));
    }
  }

  /**
   * @return {@code None} if {@code object} is {@code null}, else return {@code object}. This is
   *     just a central place ot make sure things we return from internal implementations into
   *     Skylark are properly turned into None
   */
  public static Object skylarkValueFromNullable(@Nullable Object object) {
    return object == null ? Runtime.NONE : object;
  }

  /**
   * Check that a value is either {@link Runtime#NONE} or an instance of {@code clazz}
   *
   * @param location location of evaluation
   * @param clazz the class that the object should be an instance of if not {@link Runtime#NONE}
   * @param object the object to check
   * @return the original value if it is of a correct type
   * @throws EvalException if the object is not of the correct type
   */
  public static Object validateNoneOrType(Location location, Class<?> clazz, Object object)
      throws EvalException {
    if (object == Runtime.NONE || clazz.isAssignableFrom(object.getClass())) {
      return object;
    }
    throw new EvalException(
        location,
        String.format(
            "Invalid type provided. Expected %s, got %s",
            clazz.getSimpleName(), object.getClass().getSimpleName()));
  }

  /**
   * Checks if a given value is 'Immutable'. This mostly works like {@link
   * com.google.devtools.build.lib.syntax.EvalUtils#isImmutable(java.lang.Object)}, but it can also
   * handle {@link com.google.common.collect.ImmutableCollection} and {@link
   * com.google.common.collect.ImmutableMap}
   */
  public static boolean isImmutable(Object o) {
    if (o instanceof ImmutableCollection<?>) {
      return ((ImmutableCollection<?>) o).stream().allMatch(BuckSkylarkTypes::isImmutable);
    } else if (o instanceof ImmutableMap<?, ?>) {
      return ((ImmutableMap<?, ?>) o).values().stream().allMatch(BuckSkylarkTypes::isImmutable);
    } else {
      return EvalUtils.isImmutable(o);
    }
  }
}
