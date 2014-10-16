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

import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Types;
import com.google.common.base.CaseFormat;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

class ParamInfo<T> implements Comparable<ParamInfo<T>> {

  private final TypeCoercer<?> typeCoercer;

  private final boolean isOptional;
  private final String name;
  private final String pythonName;
  private final boolean isDep;
  private final Field field;

  public ParamInfo(TypeCoercerFactory typeCoercerFactory, Field field) {
    this.field = Preconditions.checkNotNull(field);
    this.name = field.getName();
    Hint hint = field.getAnnotation(Hint.class);
    this.pythonName = determinePythonName(this.name, hint);
    this.isDep = hint != null ? hint.isDep() : Hint.DEFAULT_IS_DEP;

    isOptional = Optional.class.isAssignableFrom(field.getType());
    this.typeCoercer = typeCoercerFactory.typeCoercerForType(Types.getFirstNonOptionalType(field));
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

  /**
   * Returns the type that input values will be coerced to.
   * Return the type parameter of Optional if wrapped in Optional.
   */
  public Class<?> getResultClass() {
    return typeCoercer.getOutputClass();
  }

  public void traverse(Traversal traversal, T dto) {
    traverseHelper(typeCoercer, traversal, dto);
  }

  @SuppressWarnings("unchecked")
  private <U> void traverseHelper(TypeCoercer<U> typeCoercer, Traversal traversal, T dto) {
    U object;
    try {
      if (isOptional) {
        Optional<U> optional = (Optional<U>) field.get(dto);
        object = optional.orNull();
      } else {
        object = (U) field.get(dto);
      }
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
      ProjectFilesystem filesystem,
      BuildRuleFactoryParams params,
      Object arg,
      Map<String, ?> instance
      ) throws ParamInfoException {
    set(
        params.buildTargetParser,
        filesystem,
        params.target.getBasePath(),
        arg,
        instance.get(name));
  }

  /**
   * Sets a single property of the {@code dto}, coercing types as necessary.
   * @param filesystem {@link com.facebook.buck.util.ProjectFilesystem} used to ensure
   *        {@link java.nio.file.Path}s exist.
   * @param pathRelativeToProjectRoot The path relative to the project root that this DTO is for.
   * @param dto The constructor DTO on which the value should be set.
   * @param value The value, which may be coerced depending on the type on {@code dto}.
   */
  public void set(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object dto,
      @Nullable Object value) throws ParamInfoException {
    Object result;

    if (value == null) {
      if (isOptional) {
        result = typeCoercer.getOptionalValue();
      } else if (Number.class.isAssignableFrom(typeCoercer.getOutputClass())) {
        result = 0;
      } else if (Boolean.class.isAssignableFrom(typeCoercer.getOutputClass())) {
        result = false;
      } else {
        throw new ParamInfoException(name, "field cannot be null");
      }
    } else {
      try {
        result = typeCoercer.coerce(
            buildTargetParser,
            filesystem,
            pathRelativeToProjectRoot,
            value);
      } catch (CoerceFailedException e) {
        throw new ParamInfoException(name, e.getMessage(), e);
      }
      if (isOptional) {
        result = Optional.of(result);
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
  public int compareTo(ParamInfo<T> that) {
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

    ParamInfo<?> that = (ParamInfo<?>) obj;
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

