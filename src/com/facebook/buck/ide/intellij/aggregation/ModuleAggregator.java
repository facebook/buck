/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij.aggregation;

import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Collection;

class ModuleAggregator {

  private ModuleAggregator() {}

  public static AggregationModule aggregate(
      AggregationModule rootModule,
      Collection<AggregationModule> modulesToAggregate,
      ImmutableSet<Path> excludes) {

    ImmutableSet.Builder<TargetNode<?, ?>> targets =
        ImmutableSet.<TargetNode<?, ?>>builder().addAll(rootModule.getTargets());
    modulesToAggregate.forEach(module -> targets.addAll(module.getTargets()));

    ImmutableSet.Builder<Path> excludesBuilder = ImmutableSet.<Path>builder().addAll(excludes);
    modulesToAggregate.forEach(
        module -> {
          Path modulePath = rootModule.getModuleBasePath().relativize(module.getModuleBasePath());
          module.getExcludes().stream().map(modulePath::resolve).forEach(excludesBuilder::add);
        });

    return AggregationModule.builder()
        .from(rootModule)
        .addAllTargets(targets.build())
        .setExcludes(excludesBuilder.build())
        .build();
  }

  public static AggregationModule aggregate(
      Path moduleBasePath,
      IjModuleType moduleType,
      String aggregationTag,
      Collection<AggregationModule> modulesToAggregate,
      ImmutableSet<Path> excludes) {

    return aggregate(
        AggregationModule.builder()
            .setModuleBasePath(moduleBasePath)
            .setModuleType(moduleType)
            .setAggregationTag(aggregationTag)
            .build(),
        modulesToAggregate,
        excludes);
  }
}
