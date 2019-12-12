/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehaviorTag;
import com.facebook.buck.core.rulekey.CustomFieldDepsTag;
import com.facebook.buck.core.rulekey.DefaultFieldDeps;
import com.facebook.buck.core.rulekey.IgnoredFieldDeps;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.HasCustomDepsLogic;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.CustomBehaviorUtils;
import com.facebook.buck.rules.modern.CustomFieldDeps;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.google.common.base.Verify;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Computes all the deps by visiting all referenced SourcePaths. */
public class DepsComputingVisitor extends AbstractValueVisitor<RuntimeException> {
  private final InputRuleResolver inputRuleResolver;
  private final Consumer<BuildRule> depsBuilder;

  public DepsComputingVisitor(
      InputRuleResolver inputRuleResolver, Consumer<BuildRule> depsBuilder) {
    this.inputRuleResolver = inputRuleResolver;
    this.depsBuilder = depsBuilder;
  }

  @Override
  public <T> void visitField(
      Field field,
      T value,
      ValueTypeInfo<T> valueTypeInfo,
      List<Class<? extends CustomFieldBehaviorTag>> behavior)
      throws RuntimeException {

    Optional<CustomFieldDepsTag> depsBehavior =
        CustomBehaviorUtils.get(CustomFieldDepsTag.class, behavior);
    if (!depsBehavior.isPresent()) {
      super.visitField(field, value, valueTypeInfo, behavior);
      return;
    }

    if (depsBehavior.get() instanceof IgnoredFieldDeps) {
      return;
    }

    if (depsBehavior.get() instanceof DefaultFieldDeps) {
      @SuppressWarnings("unchecked")
      ValueTypeInfo<T> typeInfo =
          (ValueTypeInfo<T>)
              ValueTypeInfoFactory.forTypeToken(TypeToken.of(field.getGenericType()));

      typeInfo.visit(value, this);
      return;
    }

    Verify.verify(
        depsBehavior.get() instanceof CustomFieldDeps,
        "Unrecognized field deps behaviour %s.",
        depsBehavior.get().getClass().getName());

    @SuppressWarnings("unchecked")
    CustomFieldDeps<T> customDeps = (CustomFieldDeps<T>) depsBehavior.get();
    customDeps.getDeps(value, depsBuilder);
  }

  @Override
  public <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo)
      throws RuntimeException {
    if (value instanceof HasCustomDepsLogic) {
      ((HasCustomDepsLogic) value)
          .getDeps(inputRuleResolver.unsafe().getRuleFinder())
          .forEach(depsBuilder);
    } else {
      super.visitDynamic(value, classInfo);
    }
  }

  @Override
  public void visitOutputPath(OutputPath value) {}

  @Override
  public void visitSourcePath(SourcePath value) {
    inputRuleResolver.resolve(value).ifPresent(depsBuilder);
  }

  @Override
  public void visitSimple(Object value) {}

  @Override
  public void visitPath(Path path) {
    // TODO(cjhopman): Should this require some sort of explicit recognition that the Path doesn't
    // contribute to deps?
  }
}
