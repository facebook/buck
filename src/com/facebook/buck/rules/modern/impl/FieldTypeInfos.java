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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.modern.InputData;
import com.facebook.buck.rules.modern.InputPath;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.facebook.buck.rules.modern.OutputData;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

class FieldTypeInfos {
  static class SimpleFieldTypeInfo implements FieldTypeInfo<Object> {
    static final FieldTypeInfo<Object> INSTANCE = new SimpleFieldTypeInfo();

    @Override
    public Object extractRuleKeyObject(Object value) {
      return value;
    }
  }

  static class OutputPathFieldTypeInfo implements FieldTypeInfo<OutputPath> {
    public static final OutputPathFieldTypeInfo INSTANCE = new OutputPathFieldTypeInfo();

    @Override
    public void extractOutput(
        String name, OutputPath value, BiConsumer<String, OutputPath> builder) {
      builder.accept(name, value);
    }

    @Override
    public Object extractRuleKeyObject(OutputPath value) {
      return OutputPath.Internals.getPathFrom(value).toString();
    }
  }

  static class InputPathFieldTypeInfo implements FieldTypeInfo<InputPath> {
    public static final InputPathFieldTypeInfo INSTANCE = new InputPathFieldTypeInfo();

    @Override
    public void extractDep(
        InputPath value, InputRuleResolver inputRuleResolver, Consumer<BuildRule> builder) {
      Optional<BuildRule> buildRule = inputRuleResolver.resolve(value);
      buildRule.ifPresent(builder);
    }

    @Override
    public Object extractRuleKeyObject(InputPath value) {
      return InputPath.Internals.getSourcePathFrom(value);
    }
  }

  static class OutputDataFieldTypeInfo implements FieldTypeInfo<OutputData> {
    public static final OutputDataFieldTypeInfo INSTANCE = new OutputDataFieldTypeInfo();

    @Override
    public Object extractRuleKeyObject(OutputData value) {
      return "";
    }

    @Override
    public void extractOutputData(
        String name, OutputData value, BiConsumer<String, OutputData> builder) {
      builder.accept(name, value);
    }
  }

  static class InputDataFieldTypeInfo implements FieldTypeInfo<InputData> {
    public static final InputDataFieldTypeInfo INSTANCE = new InputDataFieldTypeInfo();

    @Override
    public void extractDep(
        InputData value, InputRuleResolver inputRuleResolver, Consumer<BuildRule> builder) {
      Optional<BuildRule> buildRule = inputRuleResolver.resolve(value);
      buildRule.ifPresent(builder);
    }

    @Override
    public Object extractRuleKeyObject(InputData value) {
      return value.getRuleKeyObject();
    }
  }

  static class OptionalFieldTypeInfo<T> implements FieldTypeInfo<Optional<T>> {
    private final FieldTypeInfo<T> innerType;

    OptionalFieldTypeInfo(FieldTypeInfo<T> fieldTypeInfo) {
      this.innerType = fieldTypeInfo;
    }

    @Override
    public void extractDep(
        Optional<T> value, InputRuleResolver inputRuleResolver, Consumer<BuildRule> builder) {
      value.ifPresent(o -> innerType.extractDep(o, inputRuleResolver, builder));
    }

    @Override
    public void extractOutputData(
        String name, Optional<T> value, BiConsumer<String, OutputData> builder) {
      value.ifPresent(o -> innerType.extractOutputData(name, o, builder));
    }

    @Override
    public void extractOutput(
        String name, Optional<T> value, BiConsumer<String, OutputPath> builder) {
      value.ifPresent(o -> innerType.extractOutput(name, o, builder));
    }

    @Override
    public Object extractRuleKeyObject(Optional<T> value) {
      return value.map(innerType::extractRuleKeyObject);
    }
  }

  static class IterableFieldTypeInfo<T> implements FieldTypeInfo<Iterable<T>> {
    private final FieldTypeInfo<T> innerType;

    IterableFieldTypeInfo(FieldTypeInfo<T> innerType) {
      this.innerType = innerType;
    }

    @Override
    public void extractDep(
        Iterable<T> value, InputRuleResolver inputRuleResolver, Consumer<BuildRule> builder) {
      value.forEach(o -> innerType.extractDep(o, inputRuleResolver, builder));
    }

    @Override
    public void extractOutputData(
        String name, Iterable<T> value, BiConsumer<String, OutputData> builder) {
      // TODO(cjhopman): should the name be modified to indicate position in the map?
      value.forEach(o -> innerType.extractOutputData(name, o, builder));
    }

    @Override
    public void extractOutput(
        String name, Iterable<T> value, BiConsumer<String, OutputPath> builder) {
      // TODO(cjhopman): should the name be modified to indicate position in the map?
      value.forEach(o -> innerType.extractOutput(name, o, builder));
    }

    @Override
    public Object extractRuleKeyObject(Iterable<T> value) {
      // TODO(cjhopman): this shouldn't need to be collected into a list, we should be able to add
      // a Stream<Object> (or maybe Iterable<Object>) to a rulekey.
      return RichStream.from(value)
          .map(innerType::extractRuleKeyObject)
          .collect(ImmutableList.toImmutableList());
    }
  }
}
