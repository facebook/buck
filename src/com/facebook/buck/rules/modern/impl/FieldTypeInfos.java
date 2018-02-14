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
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.facebook.buck.rules.modern.OutputPath;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

class FieldTypeInfos {
  static class SimpleFieldTypeInfo implements FieldTypeInfo<Object> {
    static final FieldTypeInfo<Object> INSTANCE = new SimpleFieldTypeInfo();
  }

  static class OutputPathFieldTypeInfo implements FieldTypeInfo<OutputPath> {
    public static final OutputPathFieldTypeInfo INSTANCE = new OutputPathFieldTypeInfo();

    @Override
    public void extractOutput(
        String name, OutputPath value, BiConsumer<String, OutputPath> builder) {
      builder.accept(name, value);
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
    public void extractOutput(
        String name, Optional<T> value, BiConsumer<String, OutputPath> builder) {
      value.ifPresent(o -> innerType.extractOutput(name, o, builder));
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
    public void extractOutput(
        String name, Iterable<T> value, BiConsumer<String, OutputPath> builder) {
      // TODO(cjhopman): should the name be modified to indicate position in the map?
      value.forEach(o -> innerType.extractOutput(name, o, builder));
    }
  }
}
