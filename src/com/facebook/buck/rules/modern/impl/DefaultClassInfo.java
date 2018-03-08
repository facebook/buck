/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.facebook.buck.rules.modern.OutputPath;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Default implementation of ClassInfo. Computes values simply by visiting all referenced fields.
 */
class DefaultClassInfo<T extends AddsToRuleKey> implements ClassInfo<T> {
  private final String type;
  private final Optional<ClassInfo<? super T>> superInfo;
  private final ImmutableList<FieldInfo<?>> fields;

  DefaultClassInfo(Class<?> clazz, Optional<ClassInfo<? super T>> superInfo) {
    this.type =
        CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, clazz.getSimpleName()).intern();
    this.superInfo = superInfo;

    ImmutableList.Builder<FieldInfo<?>> fieldsBuilder = ImmutableList.builder();
    for (Field field : clazz.getDeclaredFields()) {
      field.setAccessible(true);
      Preconditions.checkArgument(
          Modifier.isFinal(field.getModifiers()),
          "All fields referenced from a ModernBuildRule must be final (%s.%s)",
          clazz.getSimpleName(),
          field.getName());

      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }

      AddToRuleKey addAnnotation = field.getAnnotation(AddToRuleKey.class);

      Preconditions.checkState(
          addAnnotation != null,
          "All fields referenced from a ModernBuildRule must be annotated with @AddsToRuleKey. %s.%s is missing this annotation.",
          clazz.getName(),
          field.getName());

      FieldInfo<?> fieldInfo = forField(field);
      fieldsBuilder.add(fieldInfo);
    }

    if (clazz.isMemberClass()) {
      // TODO(cjhopman): This should also iterate over the outer class's class hierarchy.
      Class<?> outerClazz = clazz.getDeclaringClass();
      // I don't think this can happen, but if it does, this needs to be updated to handle it
      // correctly.
      Preconditions.checkArgument(
          !outerClazz.isAnonymousClass()
              && !outerClazz.isMemberClass()
              && !outerClazz.isLocalClass());
      for (Field field : outerClazz.getDeclaredFields()) {
        field.setAccessible(true);
        if (!Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        Preconditions.checkArgument(
            Modifier.isFinal(field.getModifiers()),
            "All static fields of a Buildable's outer class must be final (%s.%s)",
            outerClazz.getSimpleName(),
            field.getName());
      }
    }
    this.fields = fieldsBuilder.build();
  }

  /** Computes the deps of the rule. */
  @Override
  public void computeDeps(
      T ruleImpl, InputRuleResolver inputRuleResolver, Consumer<BuildRule> depsBuilder) {
    visit(ruleImpl, new DepsComputingVisitor(inputRuleResolver, depsBuilder));
  }

  /** Gets all the outputs referenced from the value. */
  @Override
  public void getOutputs(T value, Consumer<OutputPath> dataBuilder) {
    visit(value, new OutputPathVisitor(dataBuilder));
  }

  /** Gets all the inputs referenced from the value. */
  @Override
  public void getInputs(T value, Consumer<SourcePath> inputsBuilder) {
    visit(value, new InputsVisitor(inputsBuilder));
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public <E extends Exception> void visit(T value, ValueVisitor<E> visitor) throws E {
    if (superInfo.isPresent()) {
      superInfo.get().visit(value, visitor);
    }
    for (FieldInfo<?> extractor : fields) {
      extractor.visit(value, visitor);
    }
  }

  @Override
  public Optional<ClassInfo<? super T>> getSuperInfo() {
    return superInfo;
  }

  @Override
  public ImmutableCollection<FieldInfo<?>> getFieldInfos() {
    return fields;
  }

  static FieldInfo<?> forField(Field field) {
    Type type = field.getGenericType();
    ValueTypeInfo<?> valueTypeInfo = ValueTypeInfoFactory.forTypeToken(TypeToken.of(type));
    return new FieldInfo<>(field, valueTypeInfo);
  }
}
