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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Types;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Represents a single field that can be represented in buck build files, backed by an Immutable
 * DescriptionArg class
 */
public class ReflectionParamInfo extends AbstractParamInfo {

  private final Method setter;
  /**
   * Holds the closest getter for this property defined on the abstract class or interface.
   *
   * <p>Note that this may not be abstract, for instance if a @Value.Default is specified.
   */
  private final Supplier<Method> closestGetterOnAbstractClassOrInterface;

  /** Holds the getter for the concrete Immutable class. */
  private final Supplier<Method> concreteGetter;

  private final Supplier<Boolean> isOptional;

  @SuppressWarnings("PMD.EmptyCatchBlock")
  private ReflectionParamInfo(
      String name,
      TypeCoercer<?> typeCoercer,
      Method setter,
      Supplier<Method> closestGetterOnAbstractClassOrInterface,
      Supplier<Method> concreteGetter,
      Supplier<Boolean> isOptional) {
    super(name, typeCoercer);
    this.setter = setter;
    this.closestGetterOnAbstractClassOrInterface = closestGetterOnAbstractClassOrInterface;
    this.concreteGetter = concreteGetter;
    this.isOptional = isOptional;
  }

  /** Create an instance of {@link ReflectionParamInfo} */
  public static ReflectionParamInfo of(TypeCoercerFactory typeCoercerFactory, Method setter) {

    Preconditions.checkArgument(
        setter.getParameterCount() == 1,
        "Setter is expected to have exactly one parameter but had %s",
        setter.getParameterCount());
    Preconditions.checkArgument(
        setter.getName().startsWith("set"),
        "Setter is expected to have name starting with 'set' but was %s",
        setter.getName());
    Preconditions.checkArgument(
        setter.getName().length() > 3,
        "Setter must have name longer than just 'set' but was %s",
        setter.getName());

    Supplier<Method> closestGetterOnAbstractClassOrInterface =
        MoreSuppliers.memoize(() -> findClosestGetterOnAbstractClassOrInterface(setter));

    Supplier<Method> concreteGetter =
        MoreSuppliers.memoize(
            () -> {
              // This needs to get (and invoke) the concrete Immutable class's getter, not the
              // abstract
              // getter from a superclass.
              // Accordingly, we manually find the getter there, rather than using
              // closestGetterOnAbstractClassOrInterface.
              Class<?> enclosingClass = setter.getDeclaringClass().getEnclosingClass();
              if (enclosingClass == null) {
                throw new IllegalStateException(
                    String.format(
                        "Couldn't find enclosing class of Builder %s", setter.getDeclaringClass()));
              }
              Iterable<String> getterNames = getGetterNames(setter);
              for (String possibleGetterName : getterNames) {
                try {
                  return enclosingClass.getMethod(possibleGetterName);
                } catch (NoSuchMethodException e) {
                  // Handled below
                }
              }
              throw new IllegalStateException(
                  String.format(
                      "Couldn't find declared getter for %s#%s. Tried enclosing class %s methods: %s",
                      setter.getDeclaringClass(), setter.getName(), enclosingClass, getterNames));
            });
    Supplier<Boolean> isOptional =
        MoreSuppliers.memoize(
            () -> {
              Method getter = closestGetterOnAbstractClassOrInterface.get();
              Class<?> type = getter.getReturnType();
              if (CoercedTypeCache.OPTIONAL_TYPES.contains(type)) {
                return true;
              }

              if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
                return true;
              }

              // Unfortunately @Value.Default isn't retained at runtime, so we use abstract-ness
              // as a proxy for whether something has a default value.
              return !Modifier.isAbstract(getter.getModifiers());
            });

    StringBuilder builder = new StringBuilder();
    builder.append(setter.getName().substring(3, 4).toLowerCase());
    if (setter.getName().length() > 4) {
      builder.append(setter.getName().substring(4));
    }
    String name = builder.toString();

    try {
      TypeCoercer<?> typeCoercer =
          typeCoercerFactory.typeCoercerForType(setter.getGenericParameterTypes()[0]);

      return new ReflectionParamInfo(
          name,
          typeCoercer,
          setter,
          closestGetterOnAbstractClassOrInterface,
          concreteGetter,
          isOptional);
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When getting ParamInfo for %s.%s.", setter.getDeclaringClass().getName(), name);
    }
  }

  @Override
  public boolean isOptional() {
    return this.isOptional.get();
  }

  @Nullable
  @Override
  public Hint getHint() {
    return this.closestGetterOnAbstractClassOrInterface.get().getAnnotation(Hint.class);
  }

  public Method getSetter() {
    return setter;
  }

  @Override
  public Object get(Object dto) {
    Method getter = this.concreteGetter.get();
    try {
      return getter.invoke(dto);
    } catch (InvocationTargetException | IllegalAccessException e) {
      throw new IllegalStateException(
          String.format(
              "Error invoking getter %s on class %s", getter.getName(), getter.getDeclaringClass()),
          e);
    }
  }

  @Override
  public void setCoercedValue(Object dto, Object value) {
    try {
      setter.invoke(dto, value);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Type[] getGenericParameterTypes() {
    return setter.getGenericParameterTypes();
  }

  /** Returns the most-overridden getter on the abstract Immutable. */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private static Method findClosestGetterOnAbstractClassOrInterface(Method setter) {
    Iterable<Class<?>> superClasses =
        Iterables.skip(Types.getSupertypes(setter.getDeclaringClass().getEnclosingClass()), 1);
    ImmutableList<String> getterNames = getGetterNames(setter);

    for (Class<?> clazz : superClasses) {
      for (String getterName : getterNames) {
        try {
          return clazz.getDeclaredMethod(getterName);
        } catch (NoSuchMethodException e) {
          // Handled below
        }
      }
    }
    throw new IllegalStateException(
        String.format(
            "Couldn't find declared getter for %s#%s. Tried parent classes %s methods: %s",
            setter.getDeclaringClass(), setter.getName(), superClasses, getterNames));
  }

  private static ImmutableList<String> getGetterNames(Method setter) {
    String suffix = setter.getName().substring(3);
    return ImmutableList.of("get" + suffix, "is" + suffix);
  }
}
