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
package com.facebook.buck.parser;

import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import java.util.function.Supplier;

/**
 * A variant of {@link PerBuildState} that keeps additional information used by {@link
 * ParserWithConfigurableAttributes}.
 */
class PerBuildStateWithConfigurableAttributes extends PerBuildState {

  private final ConstraintResolver constraintResolver;
  private final SelectorListResolver selectorListResolver;
  private final Supplier<Platform> targetPlatform;
  private final SelectorListFactory selectorListFactory;

  PerBuildStateWithConfigurableAttributes(
      CellManager cellManager,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline,
      ParsePipeline<TargetNode<?>> targetNodeParsePipeline,
      ParsingContext parsingContext,
      ConstraintResolver constraintResolver,
      SelectorListResolver selectorListResolver,
      SelectorListFactory selectorListFactory,
      Supplier<Platform> targetPlatform) {
    super(cellManager, buildFileRawNodeParsePipeline, targetNodeParsePipeline, parsingContext);
    this.constraintResolver = constraintResolver;
    this.selectorListResolver = selectorListResolver;
    this.selectorListFactory = selectorListFactory;
    this.targetPlatform = targetPlatform;
  }

  public ConstraintResolver getConstraintResolver() {
    return constraintResolver;
  }

  public SelectorListResolver getSelectorListResolver() {
    return selectorListResolver;
  }

  public SelectorListFactory getSelectorListFactory() {
    return selectorListFactory;
  }

  public Supplier<Platform> getTargetPlatform() {
    return targetPlatform;
  }
}
