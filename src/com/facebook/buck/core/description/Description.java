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

package com.facebook.buck.core.description;

/**
 * Contains information regarding a specific {@link
 * com.facebook.buck.core.model.targetgraph.TargetNode} and the logic to create the corresponding
 * {@link com.facebook.buck.core.rules.BuildRule}
 *
 * @param <T> the argument type for the description to construct the {@link
 *     com.facebook.buck.core.rules.BuildRule}
 */
public interface Description<T> {

  /**
   * The class of the argument of this DescriptionWithDepOnTargetGraph uses in createBuildRule().
   */
  Class<T> getConstructorArgType();

  /**
   * Whether or not the build rule subgraph produced by this {@code Description} is safe to cache in
   * {@link com.facebook.buck.core.model.actiongraph.computation.IncrementalActionGraphGenerator}
   * for incremental action graph generation.
   */
  boolean producesCacheableSubgraph();
}
