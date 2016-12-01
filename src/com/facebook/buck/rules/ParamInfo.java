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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

/**
 * Represents a single field that can be represented in buck build files.
 */
public class ParamInfo implements Comparable<ParamInfo> {

  private final TypeCoercer<?> typeCoercer;

  private final boolean isOptional;
  @Nullable
  private final Object defaultValue;
  private final String name;
  private final String pythonName;
  private final boolean isDep;
  private final boolean isInput;
  private final Field field;

  private static final LoadingCache<Class<?>, Object> EMPTY_CONSTRUCTOR_ARGS =
      CacheBuilder.newBuilder().build(
          new CacheLoader<Class<?>, Object>() {
            @Override
            public Object load(Class<?> cls) throws Exception {
              try {
                return cls.newInstance();
              } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to instantiate an empty constructor arg for class " + cls + ". " +
                        "Check that the class has a public constructor that doesn't take any " +
                        "parameters and that it is not a non-static inner (or anonymous) class.",
                    e);
              }
            }
          });

  public ParamInfo(TypeCoercerFactory typeCoercerFactory, Class<?> cls, Field field) {
    this.field = field;
    this.name = field.getName();
    Hint hint = field.getAnnotation(Hint.class);
    this.pythonName = determinePythonName(this.name, hint);
    this.isDep = hint != null ? hint.isDep() : Hint.DEFAULT_IS_DEP;
    this.isInput = hint != null ? hint.isInput() : Hint.DEFAULT_IS_INPUT;

    Object emptyConstructorArg = EMPTY_CONSTRUCTOR_ARGS.getUnchecked(cls);
    try {
      this.defaultValue = field.get(emptyConstructorArg);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
    if (defaultValue != null) {
      this.isOptional = true;
    } else {
      this.isOptional = Optional.class.isAssignableFrom(field.getType());
    }
    this.typeCoercer = typeCoercerFactory.typeCoercerForType(field.getGenericType());
  }

  public String getName() {
    return name;
  }

  public boolean isOptional() {
    return isOptional;
  }

  public String getPythonName() {
    return pythonName;
  }

  public boolean isDep() {
    return isDep;
  }

  public boolean isInput() {
    return isInput;
  }

  /**
   * Returns the type that input values will be coerced to.
   * Return the type parameter of Optional if wrapped in Optional.
   */
  public Class<?> getResultClass() {
    return typeCoercer.getOutputClass();
  }

  /**
   * Traverse the value of the field on {@code dto} that is represented by this instance.
   *
   * If this field has a top level Optional type, traversal begins at the Optional value, or not at
   * all if the field is empty.
   *
   * @param traversal traversal to apply on the values.
   * @param dto the object whose field will be traversed.
   *
   * @see TypeCoercer#traverse(Object, TypeCoercer.Traversal)
   */
  public void traverse(Traversal traversal, Object dto) {
    traverseHelper(typeCoercer, traversal, dto);
  }

  @SuppressWarnings("unchecked")
  private <U> void traverseHelper(TypeCoercer<U> typeCoercer, Traversal traversal, Object dto) {
    U object;
    try {
      object = (U) field.get(dto);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }

    if (object != null) {
      typeCoercer.traverse(object, traversal);
    }
  }

  public boolean hasElementTypes(final Class<?>... types) {
    return typeCoercer.hasElementClass(types);
  }

  public void setFromParams(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      Object arg,
      Map<String, ?> instance) throws ParamInfoException {
    set(
        cellRoots,
        filesystem,
        buildTarget.getBasePath(),
        arg,
        instance.get(name));
  }

  /**
   * Sets a single property of the {@code dto}, coercing types as necessary.
   * @param cellRoots
   * @param filesystem {@link ProjectFilesystem} used to ensure
   *        {@link Path}s exist.
   * @param pathRelativeToProjectRoot The path relative to the project root that this DTO is for.
   * @param dto The constructor DTO on which the value should be set.
   * @param value The value, which may be coerced depending on the type on {@code dto}.
   */
  public void set(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object dto,
      @Nullable Object value) throws ParamInfoException {
    Object result;

    if (value == null) {
      if (defaultValue != null) {
        result = defaultValue;
      } else if (isOptional) {
        result = Optional.empty();
      } else {
        throw new ParamInfoException(name, "field cannot be null");
      }
    } else {
      try {
        result = typeCoercer.coerce(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            value);
      } catch (CoerceFailedException e) {
        throw new ParamInfoException(name, e.getMessage(), e);
      }
    }

    try {
      field.set(dto, result);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Only valid when comparing {@link ParamInfo} instances from the same description.
   */
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

  private String determinePythonName(String javaName, @Nullable Hint hint) {
    if (hint != null && !Hint.DEFAULT_NAME.equals(hint.name())) {
      return hint.name();
    }
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, javaName);
  }

  public interface Traversal extends TypeCoercer.Traversal {}
}

