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
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.concurrent.Immutable;

/**
 * List of fields to add to a generated {@code BuildConfig.java} file. Each field knows its Java
 * type, variable name, and value.
 */
@Immutable
public class BuildConfigFields implements Iterable<BuildConfigFields.Field> {

  /** An individual field in a {@link BuildConfigFields}. */
  public static final class Field {
    private final String type;
    private final String name;
    private final String value;

    /** Creates a new field with the specified parameters. */
    public Field(String type, String name, String value) {
      this.type = type;
      this.name = name;
      this.value = value;
    }

    /**
     * @return a string that could be passed to
     *     {@link BuildConfigFields#fromFieldDeclarations(Iterable)} such that it could be parsed
     *     to return a {@link Field} equal to this object.
     */
    @Override
    public String toString() {
      return String.format("%s %s = %s", type, name, value);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Field)) {
        return false;
      }

      Field that = (Field) obj;
      return Objects.equal(this.type, that.type) &&
          Objects.equal(this.name, that.name) &&
          Objects.equal(this.value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(type, name, value);
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
        String type = matcher.group("type");
        String name = matcher.group("name");
        String value = matcher.group("value");
        return new Field(type, name, value);
      } else {
        throw new HumanReadableException("Not a valid BuildConfig variable declaration: %s", input);
      }
    }
  };

  private static final BuildConfigFields EMPTY = new BuildConfigFields(
      ImmutableMap.<String, Field>of());

  private final ImmutableMap<String, Field> nameToField;

  private BuildConfigFields(ImmutableMap<String, Field> entries) {
    this.nameToField = entries;
  }

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
            return field.name;
          }
        });
    return new BuildConfigFields(entries);
  }

  /**
   * @return A new {@link BuildConfigFields} with all of the fields from this object, combined with
   *     all of the fields from the specified {@code fields}. If both objects have fields with the
   *     same name, the entry from the {@code fields} parameter wins.
   */
  public BuildConfigFields putAll(BuildConfigFields fields) {

    ImmutableMap.Builder<String, Field> nameToFieldBuilder = ImmutableMap.builder();
    nameToFieldBuilder.putAll(fields.nameToField);
    for (Field field : this.nameToField.values()) {
      if (!fields.nameToField.containsKey(field.name)) {
        nameToFieldBuilder.put(field.name, field);
      }
    }
    return new BuildConfigFields(nameToFieldBuilder.build());
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
    for (Field field : nameToField.values()) {
      String type = field.type;
      if ("boolean".equals(type)) {
        // type is a non-numeric primitive.
        boolean isTrue = "true".equals(field.value);
        if (!(isTrue || "false".equals(field.value))) {
          throw new HumanReadableException("expected boolean literal but was: %s", field.value);
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
        builder.append(prefix + "boolean " + field.name + " = " + value + ";\n");
      } else {
        String typeSafeZero = PRIMITIVE_NUMERIC_TYPE_NAMES.contains(type) ? "0" : "null";
        String defaultValue = field.value;
        if (!useConstantExpressions) {
          defaultValue = "!Boolean.parseBoolean(null) ? " + defaultValue + " : " + typeSafeZero;
        }
        builder.append(prefix + type + " " + field.name + " = " + defaultValue + ";\n");
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
    return nameToField.values().iterator();
  }

  /**
   * @return value that represents the data stored in this object such that it can be used to
   *     represent this object in a {@link com.facebook.buck.rules.RuleKey}.
   */
  @Override
  public String toString() {
    return Joiner.on(';').join(nameToField.values());
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof BuildConfigFields)) {
      return false;
    }

    BuildConfigFields that = (BuildConfigFields) obj;
    return Objects.equal(this.nameToField, that.nameToField);
  }

  @Override
  public int hashCode() {
    return nameToField.hashCode();
  }
}
