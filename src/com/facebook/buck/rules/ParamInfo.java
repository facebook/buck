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

import static com.facebook.buck.model.BuildTarget.BUILD_TARGET_PREFIX;
import static com.facebook.buck.rules.BuildRuleFactoryParams.GENFILE_PREFIX;
import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.LOWER_UNDERSCORE;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.primitives.Primitives;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import javax.annotation.Nullable;

/**
 * Parameter information derived from the object returned by
 * {@link Description#createUnpopulatedConstructorArg()}.
 */
// DO NOT EXPOSE OUT OF PACKAGE.
class ParamInfo implements Comparable<ParamInfo> {
  /**
   * Name of the parameter using Java's normalFieldCasing.
   */
  private final String name;

  /**
   * Name of the parameter using Python's snake_casing.
   */
  private final String pyName;

  /**
   * Is the property optional (indicated by being Optional or @Hinted with a default value.
   */
  private final boolean isOptional;

  /**
   * If the value is a List or Set, which of the two is it.
   */
  @Nullable
  private final Class<?> containerType;

  /**
   * The type of the field, or the contained type of an Optional or a Collection.
   */
  private final Class<?> type;
  private final Path pathRelativeToProjectRoot;

  private Field field;

  /**
   * @param pathRelativeToProjectRoot The path to the directory containing the build file this param
   *     is for.
   * @param field The field in the constructor arg that this represents.
   */
  public ParamInfo(Path pathRelativeToProjectRoot, Field field) {
    this.pathRelativeToProjectRoot = Preconditions.checkNotNull(pathRelativeToProjectRoot);

    this.field = Preconditions.checkNotNull(field);

    this.name = field.getName();
    Hint hint = field.getAnnotation(Hint.class);
    this.pyName = determinePyName(this.name, hint);

    Class<?> rawType = field.getType();
    Type genericType = field.getGenericType();

    this.isOptional = Optional.class.isAssignableFrom(rawType);

    // TODO(simons): Check for a wildcard here.
    if (List.class.isAssignableFrom(rawType) || Set.class.isAssignableFrom(rawType)) {
      this.containerType = rawType;
      this.type = determineGenericType(genericType);
    } else if (Optional.class.isAssignableFrom(rawType)) {
      this.type = unwrapGenericType(genericType);

      Class<?> container = null;

      if (genericType instanceof ParameterizedType) {
        Type containerType = ((ParameterizedType) genericType).getActualTypeArguments()[0];
        if (containerType instanceof ParameterizedType) {
          Type rawContainerType = ((ParameterizedType) containerType).getRawType();
          if (!(rawContainerType instanceof Class)) {
            throw new RuntimeException("Container type isn't a class: " + rawContainerType);
          }
          container = (Class<?>) rawContainerType;
          if (!Collection.class.isAssignableFrom(container)) {
            throw new RuntimeException("Cannot determine container type: " + container);
          }
        }
      }
      containerType = container;
    } else {
      this.containerType = null;
      this.type = Primitives.wrap(rawType);
    }
  }

  String getName() {
    return name;
  }

  String getPythonName() {
    return pyName;
  }

  boolean isOptional() {
    return isOptional;
  }

  @Nullable
  Class<?> getContainerType() {
    return containerType;
  }

  Class<?> getType() {
    return type;
  }

  private Class<?> unwrapGenericType(Type type) {
    if (!(type instanceof ParameterizedType)) {
      return (Class<?>) type;
    }

    Type[] types = ((ParameterizedType) type).getActualTypeArguments();
    if (types.length != 1) {
      throw new IllegalArgumentException("Unable to determine generic type");
    }

    if (types[0] instanceof WildcardType) {
      throw new IllegalStateException("Generic types must be specific: " + type);
    }

    if (types[0] instanceof ParameterizedType) {
      return unwrapGenericType(types[0]);
    }
    return (Class<?>) types[0];
  }

  private String determinePyName(String javaName, @Nullable Hint hint) {
    if (hint != null) {
      return hint.name();
    }
    return LOWER_CAMEL.to(LOWER_UNDERSCORE, javaName);
  }

  private Class<?> determineGenericType(Type genericType) {
    if (!(genericType instanceof ParameterizedType)) {
      throw new IllegalArgumentException("Collection type was not generic");
    }

    Type[] types = ((ParameterizedType) genericType).getActualTypeArguments();
    if (types.length != 1) {
      throw new IllegalArgumentException("Unable to determine generic type");
    }

    if (types[0] instanceof WildcardType) {
      WildcardType wild = (WildcardType) types[0];

      if (Object.class.equals(wild.getUpperBounds()[0])) {
        throw new IllegalArgumentException("Generic types must be specific: " + genericType);
      }
      return Primitives.wrap((Class<?>) wild.getUpperBounds()[0]);
    }

    return Primitives.wrap((Class<?>) types[0]);
  }

  public void setFromParams(
      BuildRuleResolver ruleResolver,
      Object dto,
      BuildRuleFactoryParams params) {
    if (containerType != null) {
      set(ruleResolver, dto, params.getOptionalListAttribute(name));
    } else if (Path.class.isAssignableFrom(type) ||
        SourcePath.class.isAssignableFrom(type) ||
        String.class.equals(type)) {
      set(ruleResolver, dto, params.getOptionalStringAttribute(name).orNull());
    } else if (Number.class.isAssignableFrom(type)) {
      set(ruleResolver, dto, params.getRequiredLongAttribute(name));
    } else if (BuildTarget.class.isAssignableFrom(type)) {
      Optional<BuildTarget> optionalTarget = params.getOptionalBuildTarget(name);
      set(ruleResolver, dto, optionalTarget.orNull());
    } else if (BuildRule.class.isAssignableFrom(type)) {
      Optional<BuildTarget> optionalTarget = params.getOptionalBuildTarget(name);
      set(ruleResolver, dto, optionalTarget.orNull());
    } else if (Boolean.class.equals(type)) {
      set(ruleResolver, dto, params.getBooleanAttribute(name));
    } else {
      throw new RuntimeException("Unknown type: " + type);
    }
  }

  /**
   * Sets a single property of the {@code dto}, coercing types as necessary.
   *
   * @param resolver {@link BuildRuleResolver} used for {@link BuildRule} instances.
   * @param dto The constructor DTO on which the value should be set.
   * @param value The value, which may be coerced depending on the type on {@code dto}.
   */
  public void set(BuildRuleResolver resolver, Object dto, @Nullable Object value) {
    if (value == null) {
      if (!isOptional) {
        throw new IllegalArgumentException(String.format(
            "%s cannot be null. Build file can be found in %s.",
            dto, pathRelativeToProjectRoot));
      }

      value = Optional.absent();
    } else if (isOptional && matchesDefaultValue(value)) {
      if (value instanceof Collection) {
        value = asCollection(resolver, Lists.newArrayList());
        value = Optional.of(value);
      } else {
        value = Optional.absent();
      }
    } else if (containerType == null) {
      value = coerceToExpectedType(resolver, value);
      if (isOptional) {
        value = Optional.of(value);
      }
    } else {
      value = asCollection(resolver, value);
      if (isOptional) {
        value = Optional.of(value);
      }
    }

    try {
      field.set(dto, value);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException(String.format(
          "Unable to convert '%s' to %s in build file in %s",
          value, type, pathRelativeToProjectRoot));
    }
  }

  private boolean matchesDefaultValue(Object value) {
    if (value instanceof String && "".equals(value)) {
      return true;
    }

    if (value instanceof Number && ((Number)value).intValue() == 0) {
      return true;
    }

    if (Boolean.FALSE.equals(value)) {
      return true;
    }

    if (value instanceof List && ((List<?>) value).isEmpty()) {
      return true;
    }

    return false;
  }

  private Object coerceToExpectedType(BuildRuleResolver ruleResolver, Object value) {
    if (BuildRule.class.isAssignableFrom(type)) {
      BuildTarget target = asBuildTarget(value);
      return ruleResolver.get(target);
    }

    if (BuildTarget.class.isAssignableFrom(type)) {
      return asBuildTarget(value);
    }

    // All paths should be relative to the base path.
    if (Path.class.isAssignableFrom(type)) {
      return asNormalizedPath(value);
    }

    if (SourcePath.class.isAssignableFrom(type)) {
      BuildTarget target = asBuildTarget(value);
      if (target != null) {
        return new BuildTargetSourcePath(target);
      }
      Path path = asNormalizedPath(value);
      return new FileSourcePath(path.toString());
    }

    if (value instanceof Number) {
      Number num = (Number) value;
      if (Double.class.equals(type)) {
        return num.doubleValue();
      } else if (Integer.class.equals(type)) {
        return num.intValue();
      } else if (Float.class.equals(type)) {
        return num.floatValue();
      } else if (Long.class.equals(type)) {
        return num.longValue();  // not strictly necessary, but included for completeness.
      } else if (Short.class.equals(type)) {
        return num.shortValue();
      }
    }

    // We're going to cheat and let the JVM take the strain of converting between primitive and
    // object wrapper types, but it has a habit of coercing to a String that should be avoided.
    if (String.class.equals(type) && !String.class.equals(value.getClass())) {
      throw new IllegalArgumentException(
          String.format("Unable to convert '%s' to %s", value, type));
    }

    return value;
  }

  @Nullable
  private BuildTarget asBuildTarget(Object value) {
    if (value instanceof BuildTarget) {
      return (BuildTarget) value;
    }

    Preconditions.checkArgument(value instanceof String,
        "Expected argument '%s' to be a build target", value);

    String param = (String) value;

    if (param.startsWith(GENFILE_PREFIX)) {
      return null;
    }

    int colon = param.indexOf(':');
    if (colon == 0 && param.length() > 1) {
      return new BuildTarget(
          BUILD_TARGET_PREFIX + pathRelativeToProjectRoot.toString(),
          param.substring(1));
    } else if (colon > 0 && param.length() > 2) {
      return new BuildTarget(param.substring(0, colon), param.substring(colon + 1));
    }
    return null;
  }

  private Path asNormalizedPath(Object value) {
    Preconditions.checkArgument(value instanceof String,
        "Expected argument '%s' to be a string in build file in %s",
        value, pathRelativeToProjectRoot);

    // genfiles point to a path that's already relative to the buck-gen directory. Everything else
    // is assumed to be a file relative to the pathRelativeToProjectRoot.
    String path = (String) value;
    if (path.startsWith(GENFILE_PREFIX)) {
      path = path.substring(GENFILE_PREFIX.length());

      return Paths.get(BuckConstant.GEN_DIR)
          .resolve(pathRelativeToProjectRoot)
          .resolve(path)
          .normalize();
    }

    return pathRelativeToProjectRoot.resolve(path).normalize();
  }

  @SuppressWarnings("unchecked")
  private Object asCollection(BuildRuleResolver ruleResolver, Object value) {
    if (!(value instanceof Collection)) {
      throw new IllegalArgumentException(String.format(
          "May not set '%s' on a collection type in build file in %s",
          value, pathRelativeToProjectRoot));
    }

    List<Object> collection = Lists.newArrayList();

    for (Object obj : (Iterable<Object>) value) {
      collection.add(coerceToExpectedType(ruleResolver, obj));
    }

    if (SortedSet.class.isAssignableFrom(containerType)) {
      return ImmutableSortedSet.copyOf(collection);
    } else if (Set.class.isAssignableFrom(containerType)) {
      return ImmutableSet.copyOf(collection);
    } else {
      return ImmutableList.copyOf(collection);
    }
  }

  /**
   * Only valid when comparing {@link ParamInfo} instances from the same description.
   */
  @Override
  public int compareTo(ParamInfo that) {
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
}
