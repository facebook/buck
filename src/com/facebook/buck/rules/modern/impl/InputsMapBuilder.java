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
import com.facebook.buck.core.rulekey.CustomFieldBehaviorTag;
import com.facebook.buck.core.rulekey.CustomFieldInputsTag;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.rules.modern.HasCustomInputsLogic;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.CustomBehaviorUtils;
import com.facebook.buck.rules.modern.CustomFieldInputs;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * InputsMapBuilder is used to find all the input SourcePaths of a Buildable. For each instance of
 * an {@link AddsToRuleKey} object that directly references other AddsToRuleKey objects or
 * SourcePaths, or {@link Data} node will be created. This allows two things: (1) we don't need to
 * traverse the object structure of shared appendables multiple times and (2) it is easy for
 * consumers to cache computed information based on this derived graph.
 */
public class InputsMapBuilder {
  private final ConcurrentHashMap<AddsToRuleKey, Data> cache = new ConcurrentHashMap<>();

  /**
   * Holds the information derived from a rulekey appendable. For a particular appendable, the same
   * Data instance will always be returned.
   *
   * <p>The Data objects form a projection of the rulekey appendable graph with edges into an object
   * with no (directly or indirectly) referenced inputs removed.
   */
  public static class Data {
    private final ImmutableList<SourcePath> paths;
    private final ImmutableList<Data> children;

    public Data(ImmutableList<SourcePath> paths, ImmutableList<Data> children) {
      this.paths = paths;
      this.children = children;
    }

    public ImmutableList<SourcePath> getPaths() {
      return paths;
    }

    public ImmutableList<Data> getChildren() {
      return children;
    }

    public void forAllData(Consumer<Data> dataConsumer) {
      dataConsumer.accept(this);
      getChildren().forEach(child -> child.forAllData(dataConsumer));
    }
  }

  public InputsMapBuilder() {}

  /** Computes the "inputs" graph of rule key appendables. Returns the root of that graph. */
  public <T extends AddsToRuleKey> Data getInputs(T instance) {
    return getInputs(instance, DefaultClassInfoFactory.forInstance(instance));
  }

  public Data getInputs(ModernBuildRule<?> rule) {
    return getInputs(rule.getBuildable());
  }

  /** See getInputs(T instance) above. */
  public <T extends AddsToRuleKey> Data getInputs(T instance, ClassInfo<T> classInfo) {
    Data value = cache.get(instance);
    if (value != null) {
      return value;
    }
    Visitor visitor = new Visitor();
    if (instance instanceof HasCustomInputsLogic) {
      ((HasCustomInputsLogic) instance).computeInputs(visitor.paths::add);
    } else {
      classInfo.visit(instance, visitor);
    }
    return Objects.requireNonNull(
        cache.computeIfAbsent(
            instance, ignored -> new Data(visitor.paths.build(), visitor.children.build())));
  }

  private class Visitor extends AbstractValueVisitor<RuntimeException> {
    private final ImmutableList.Builder<SourcePath> paths = ImmutableList.builder();
    private final ImmutableList.Builder<Data> children = ImmutableList.builder();

    public Visitor() {}

    @Override
    public <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo) {
      Data data = getInputs(value, classInfo);
      if (!data.getChildren().isEmpty() || !data.getPaths().isEmpty()) {
        children.add(data);
      }
    }

    @Override
    public <T> void visitField(
        Field field,
        T value,
        ValueTypeInfo<T> valueTypeInfo,
        List<Class<? extends CustomFieldBehaviorTag>> behavior)
        throws RuntimeException {
      Optional<CustomFieldInputsTag> inputsBehavior =
          CustomBehaviorUtils.get(CustomFieldInputsTag.class, behavior);
      if (inputsBehavior.isPresent()) {
        if (inputsBehavior.get() instanceof DefaultFieldInputs) {
          @SuppressWarnings("unchecked")
          ValueTypeInfo<T> typeInfo =
              (ValueTypeInfo<T>)
                  ValueTypeInfoFactory.forTypeToken(TypeToken.of(field.getGenericType()));

          typeInfo.visit(value, this);
          return;
        }

        if (inputsBehavior.get() instanceof IgnoredFieldInputs) {
          return;
        }

        Verify.verify(
            inputsBehavior.get() instanceof CustomFieldInputs,
            "Unrecognized field inputs behavior %s.",
            inputsBehavior.get().getClass().getName());

        @SuppressWarnings("unchecked")
        CustomFieldInputs<T> customInputs = (CustomFieldInputs<T>) inputsBehavior.get();
        customInputs.getInputs(value, paths::add);
        return;
      }

      valueTypeInfo.visit(value, this);
    }

    @Override
    protected void visitSimple(Object value) {
      // ignored
    }

    @Override
    public void visitPath(Path path) throws RuntimeException {}

    @Override
    public void visitOutputPath(OutputPath value) throws RuntimeException {}

    @Override
    public void visitSourcePath(SourcePath value) {
      paths.add(value);
    }
  }
}
