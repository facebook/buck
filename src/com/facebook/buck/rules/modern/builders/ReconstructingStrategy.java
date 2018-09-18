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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.build.engine.BuildExecutorRunner;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.AbstractBuildRuleResolver;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.Deserializer;
import com.facebook.buck.rules.modern.Deserializer.DataProvider;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.Serializer;
import com.facebook.buck.rules.modern.Serializer.Delegate;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A strategy that simply serializes and deserializes the rule in-memory and runs the deserialized
 * version. Useful for debugging serialization/deserialization issues.
 */
class ReconstructingStrategy extends AbstractModernBuildRuleStrategy {
  private final Map<HashCode, byte[]> dataMap;
  private final AtomicInteger id;
  private final Delegate delegate;
  private final Serializer serializer;
  private final Deserializer deserializer;

  ReconstructingStrategy(
      SourcePathRuleFinder ruleFinder, CellPathResolver cellResolver, Cell rootCell) {
    dataMap = new ConcurrentHashMap<>();
    id = new AtomicInteger();
    delegate =
        (instance, data, children) -> {
          HashCode hash = HashCode.fromInt(id.incrementAndGet());
          dataMap.put(hash, data);
          return hash;
        };
    serializer = new Serializer(ruleFinder, cellResolver, delegate);
    deserializer =
        new Deserializer(
            name ->
                rootCell
                    .getCellProvider()
                    .getCellByPath(cellResolver.getCellPathOrThrow(name))
                    .getFilesystem(),
            Class::forName,
            () -> DefaultSourcePathResolver.from(ruleFinder),
            rootCell.getToolchainProvider());
  }

  DataProvider getProvider(HashCode hash) {
    return new DataProvider() {
      @Override
      public InputStream getData() {
        return new ByteArrayInputStream(dataMap.get(hash));
      }

      @Override
      public DataProvider getChild(HashCode hash) {
        return getProvider(hash);
      }
    };
  }

  @Override
  public void build(
      ListeningExecutorService service, BuildRule rule, BuildExecutorRunner executorRunner) {
    Preconditions.checkState(rule instanceof ModernBuildRule);

    executorRunner.runWithExecutor(
        (executionContext, buildRuleBuildContext, buildableContext, stepRunner) -> {
          ModernBuildRule<?> converted = (ModernBuildRule<?>) rule;

          Buildable original = converted.getBuildable();
          HashCode hash = serializer.serialize(original);
          Buildable reconstructed = deserializer.deserialize(getProvider(hash), Buildable.class);
          ModernBuildRule.injectFieldsIfNecessary(
              rule.getProjectFilesystem(),
              rule.getBuildTarget(),
              reconstructed,
              new SourcePathRuleFinder(
                  new AbstractBuildRuleResolver() {
                    @Override
                    public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
                      throw new RuntimeException("Cannot resolve rules in deserialized MBR state.");
                    }
                  }));

          for (Step step :
              ModernBuildRule.stepsForBuildable(
                  buildRuleBuildContext,
                  reconstructed,
                  rule.getProjectFilesystem(),
                  rule.getBuildTarget())) {
            stepRunner.runStepForBuildTarget(
                executionContext, step, Optional.of(rule.getBuildTarget()));
          }

          converted.recordOutputs(buildableContext);
        });
  }
}
