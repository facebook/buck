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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Types;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Represents a single field that can be represented in buck build files. */
public class ParamInfo implements Comparable<ParamInfo> {

  private final TypeCoercer<?> typeCoercer;

  private final String name;

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
  public ParamInfo(TypeCoercerFactory typeCoercerFactory, Method setter) {
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
    this.setter = setter;

    this.closestGetterOnAbstractClassOrInterface =
        MoreSuppliers.memoize(this::findClosestGetterOnAbstractClassOrInterface);

    this.concreteGetter =
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
              Iterable<String> getterNames = getGetterNames();
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
    this.isOptional =
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
    this.name = builder.toString();

    try {
      this.typeCoercer =
          typeCoercerFactory.typeCoercerForType(setter.getGenericParameterTypes()[0]);
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When getting ParamInfo for %s.%s.", setter.getDeclaringClass().getName(), name);
    }
  }

  public String getName() {
    return name;
  }

  public TypeCoercer<?> getTypeCoercer() {
    return typeCoercer;
  }

  public boolean isOptional() {
    return this.isOptional.get();
  }

  public String getPythonName() {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, getName());
  }

  public boolean isDep() {
    Hint hint = getHint();
    if (hint != null) {
      return hint.isDep();
    }
    return Hint.DEFAULT_IS_DEP;
  }

  /** @see Hint#isTargetGraphOnlyDep() */
  public boolean isTargetGraphOnlyDep() {
    Hint hint = getHint();
    if (hint != null && hint.isTargetGraphOnlyDep()) {
      Preconditions.checkState(hint.isDep(), "Conditional deps are only applicable for deps.");
      return true;
    }
    return Hint.DEFAULT_IS_TARGET_GRAPH_ONLY_DEP;
  }

  public boolean isInput() {
    Hint hint = getHint();
    if (hint != null) {
      return hint.isInput();
    }
    return Hint.DEFAULT_IS_INPUT;
  }

  private Hint getHint() {
    return this.closestGetterOnAbstractClassOrInterface.get().getAnnotation(Hint.class);
  }

  /**
   * Returns the type that input values will be coerced to. Return the type parameter of Optional if
   * wrapped in Optional.
   */
  public Class<?> getResultClass() {
    return typeCoercer.getOutputClass();
  }

  public Method getSetter() {
    return setter;
  }

  /**
   * Traverse the value of the field on {@code dto} that is represented by this instance.
   *
   * <p>If this field has a top level Optional type, traversal begins at the Optional value, or not
   * at all if the field is empty.
   *
   * @param traversal traversal to apply on the values.
   * @param dto the object whose field will be traversed.
   * @see TypeCoercer#traverse(CellPathResolver, Object, TypeCoercer.Traversal)
   */
  public void traverse(CellPathResolver cellPathResolver, Traversal traversal, Object dto) {
    traverseHelper(cellPathResolver, typeCoercer, traversal, dto);
  }

  @SuppressWarnings("unchecked")
  private <U> void traverseHelper(
      CellPathResolver cellPathResolver,
      TypeCoercer<U> typeCoercer,
      Traversal traversal,
      Object dto) {
    U object = (U) get(dto);
    if (object != null) {
      typeCoercer.traverse(cellPathResolver, object, traversal);
    }
  }

  /** Get the value of this param as set on dto. */
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

  public boolean hasElementTypes(Class<?>... types) {
    return typeCoercer.hasElementClass(types);
  }

  public void setFromParams(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      Object arg,
      Map<String, ?> instance)
      throws ParamInfoException {
    set(cellRoots, filesystem, buildTarget.getBasePath(), arg, instance.get(name));
  }

  /**
   * Sets a single property of the {@code dto}, coercing types as necessary.
   *
   * @param cellRoots
   * @param filesystem {@link ProjectFilesystem} used to ensure {@link Path}s exist.
   * @param pathRelativeToProjectRoot The path relative to the project root that this DTO is for.
   * @param dto The constructor DTO on which the value should be set.
   * @param value The value, which may be coerced depending on the type on {@code dto}.
   */
  public void set(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object dto,
      @Nullable Object value)
      throws ParamInfoException {
    if (value == null) {
      return;
    }
    try {
      setCoercedValue(
          dto, typeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, value));
    } catch (CoerceFailedException e) {
      throw new ParamInfoException(name, e.getMessage(), e);
    }
  }

  /**
   * Set the param on dto to value, assuming value has already been coerced.
   *
   * <p>This is useful for things like making copies of dtos.
   */
  public void setCoercedValue(Object dto, Object value) {
    try {
      setter.invoke(dto, value);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns the most-overridden getter on the abstract Immutable. */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private Method findClosestGetterOnAbstractClassOrInterface() {
    Iterable<Class<?>> superClasses =
        Iterables.skip(Types.getSupertypes(setter.getDeclaringClass().getEnclosingClass()), 1);
    ImmutableList<String> getterNames = getGetterNames();

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

  private ImmutableList<String> getGetterNames() {
    String suffix = setter.getName().substring(3);
    return ImmutableList.of("get" + suffix, "is" + suffix);
  }

  /** Only valid when comparing {@link ParamInfo} instances from the same description. */
  @Override
  public int compareTo(ParamInfo that) {
    if (this == that) {
      return 0;
    }

    return this.name.compareTo(that.name);
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ParamInfo)) {
      return false;
    }

    ParamInfo that = (ParamInfo) obj;
    return name.equals(that.getName());
  }

  public interface Traversal extends TypeCoercer.Traversal {}
}
