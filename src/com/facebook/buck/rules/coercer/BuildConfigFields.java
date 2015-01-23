/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * List of fields to add to a generated {@code BuildConfig.java} file. Each field knows its Java
 * type, variable name, and value.
 */
@Value.Nested
@Value.Immutable
@BuckStyleImmutable
public abstract class BuildConfigFields implements Iterable<BuildConfigFields.Field> {

  /** An individual field in a {@link BuildConfigFields}. */
  @Value.Immutable
  @BuckStyleImmutable
  public abstract static class Field {

    @Value.Parameter
    public abstract String getType();

    @Value.Parameter
    public abstract String getName();

    @Value.Parameter
    public abstract String getValue();

    /**
     * @return a string that could be passed to
     *     {@link BuildConfigFields#fromFieldDeclarations(Iterable)} such that it could be parsed
     *     to return a {@link Field} equal to this object.
     */
    @Override
    public String toString() {
      return String.format("%s %s = %s", getType(), getName(), getValue());
    }

  }

  private static final Pattern VARIABLE_DEFINITION_PATTERN = Pattern.compile(
      "(?<type>[a-zA-Z_$][a-zA-Z0-9_.<>]+)" +
      "\\s+" +
      "(?<name>[a-zA-Z_$][a-zA-Z0-9_$]+)" +
      "\\s*=\\s*" +
      "(?<value>.+)");

  private static final ImmutableSet<String> PRIMITIVE_NUMERIC_TYPE_NAMES = ImmutableSet.of(
      "byte",
      "char",
      "double",
      "float",
      "int",
      "long",
      "short");

  private static final Function<String, Field> TRANSFORM = new Function<String, Field>() {
    @Override
    public Field apply(String input) {
      Matcher matcher = VARIABLE_DEFINITION_PATTERN.matcher(input);
      if (matcher.matches()) {
        return ImmutableBuildConfigFields.Field.builder()
            .setType(matcher.group("type"))
            .setName(matcher.group("name"))
            .setValue(matcher.group("value"))
            .build();
      } else {
        throw new HumanReadableException("Not a valid BuildConfig variable declaration: %s", input);
      }
    }
  };

  private static final BuildConfigFields EMPTY = ImmutableBuildConfigFields.of(
      ImmutableMap.<String, Field>of());

  @Value.Parameter
  protected abstract Map<String, Field> getNameToField();

  public static BuildConfigFields fromFieldDeclarations(Iterable<String> declarations) {
    return fromFields(FluentIterable.from(declarations).transform(TRANSFORM));
  }

  /** @return a {@link BuildConfigFields} with no fields */
  public static BuildConfigFields empty() {
    return EMPTY;
  }

  /** @return a {@link BuildConfigFields} that contains the specified fields in iteration order. */
  public static BuildConfigFields fromFields(Iterable<Field> fields) {
    ImmutableMap<String, Field> entries = FluentIterable
        .from(fields)
        .uniqueIndex(new Function<Field, String>() {
          @Override
          public String apply(Field field) {
            return field.getName();
          }
        });
    return ImmutableBuildConfigFields.builder()
        .putAllNameToField(entries)
        .build();
  }

  /**
   * @return A new {@link BuildConfigFields} with all of the fields from this object, combined with
   *     all of the fields from the specified {@code fields}. If both objects have fields with the
   *     same name, the entry from the {@code fields} parameter wins.
   */
  public BuildConfigFields putAll(BuildConfigFields fields) {

    ImmutableMap.Builder<String, Field> nameToFieldBuilder = ImmutableMap.builder();
    nameToFieldBuilder.putAll(fields.getNameToField());
    for (Field field : this.getNameToField().values()) {
      if (!fields.getNameToField().containsKey(field.getName())) {
        nameToFieldBuilder.put(field.getName(), field);
      }
    }
    return ImmutableBuildConfigFields.of(nameToFieldBuilder.build());
  }

  /**
   * Creates the Java code for a {@code BuildConfig.java} file in the specified {@code javaPackage}.
   * @param source The build target of the rule that is responsible for generating this
   *     BuildConfig.java file.
   * @param javaPackage The Java package for the generated file.
   * @param useConstantExpressions Whether the value of each field in the generated Java code should
   *     be the literal value from the {@link Field} (i.e., a constant expression) or a
   *     non-constant-expression that is guaranteed to evaluate to the literal value.
   */
  public String generateBuildConfigDotJava(
      BuildTarget source,
      String javaPackage,
      boolean useConstantExpressions) {

    StringBuilder builder = new StringBuilder();
    // By design, we drop the flavor from the BuildTarget (if present), so this debug text makes
    // more sense to users.
    builder.append(String.format(
        "// Generated by %s. DO NOT MODIFY.\n",
        source.getUnflavoredTarget()));
    builder.append("package ").append(javaPackage).append(";\n");
    builder.append("public class BuildConfig {\n");
    builder.append("  private BuildConfig() {}\n");

    final String prefix = "  public static final ";
    for (Field field : getNameToField().values()) {
      String type = field.getType();
      if ("boolean".equals(type)) {
        // type is a non-numeric primitive.
        boolean isTrue = "true".equals(field.getValue());
        if (!(isTrue || "false".equals(field.getValue()))) {
          throw new HumanReadableException(
              "expected boolean literal but was: %s",
              field.getValue());
        }
        String value;
        if (useConstantExpressions) {
          value = String.valueOf(isTrue);
        } else {
          value = "Boolean.parseBoolean(null)";
          if (isTrue) {
            value = "!" + value;
          }
        }
        builder.append(prefix + "boolean " + field.getName() + " = " + value + ";\n");
      } else {
        String typeSafeZero = PRIMITIVE_NUMERIC_TYPE_NAMES.contains(type) ? "0" : "null";
        String defaultValue = field.getValue();
        if (!useConstantExpressions) {
          defaultValue = "!Boolean.parseBoolean(null) ? " + defaultValue + " : " + typeSafeZero;
        }
        builder.append(prefix + type + " " + field.getName() + " = " + defaultValue + ";\n");
      }
    }

    builder.append("}\n");
    return builder.toString();
  }

  /**
   * @return iterator that enumerates the fields used to construct this {@link BuildConfigFields}.
   *     The {@link Iterator#remove()} method of the return value is not supported.
   */
  @Override
  public Iterator<Field> iterator() {
    return getNameToField().values().iterator();
  }

  /**
   * @return value that represents the data stored in this object such that it can be used to
   *     represent this object in a {@link com.facebook.buck.rules.RuleKey}.
   */
  @Override
  public String toString() {
    return Joiner.on(';').join(getNameToField().values());
  }

}
