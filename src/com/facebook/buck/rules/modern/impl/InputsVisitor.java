/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.modern.HasCustomInputsLogic;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.CustomBehaviorUtils;
import com.facebook.buck.rules.modern.CustomFieldInputs;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/** Enumerates all the referenced SourcePaths. */
public class InputsVisitor extends AbstractValueVisitor<RuntimeException> {
  private final Consumer<SourcePath> inputsBuilder;

  public InputsVisitor(Consumer<SourcePath> inputsBuilder) {
    this.inputsBuilder = inputsBuilder;
  }

  @Override
  public <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo)
      throws RuntimeException {
    if (value instanceof HasCustomInputsLogic) {
      ((HasCustomInputsLogic) value).computeInputs(inputsBuilder::accept);
    } else {
      super.visitDynamic(value, classInfo);
    }
  }

  @Override
  public <T> void visitField(
      Field field,
      T value,
      ValueTypeInfo<T> valueTypeInfo,
      Optional<CustomFieldBehavior> customBehavior)
      throws RuntimeException {
    Optional<?> inputsTag = CustomBehaviorUtils.get(customBehavior, CustomFieldInputs.class);
    if (inputsTag.isPresent()) {
      @SuppressWarnings("unchecked")
      CustomFieldInputs<T> customInputs = (CustomFieldInputs<T>) inputsTag.get();
      customInputs.getInputs(value, inputsBuilder);
    } else {
      super.visitField(field, value, valueTypeInfo, customBehavior);
    }
  }

  @Override
  public void visitOutputPath(OutputPath value) {}

  @Override
  public void visitSourcePath(SourcePath value) {
    inputsBuilder.accept(value);
  }

  @Override
  public void visitSimple(Object value) {}

  @Override
  public void visitPath(Path path) throws RuntimeException {
    // TODO(cjhopman): Should this require some sort of explicit recognition that the Path doesn't
    // contribute to inputs?
  }
}
