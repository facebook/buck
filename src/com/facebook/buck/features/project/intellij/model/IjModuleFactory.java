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
package com.facebook.buck.features.project.intellij.model;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Set;

/** Builds {@link IjModule}s out of {@link TargetNode}s. */
public interface IjModuleFactory {
  /**
   * Create an {@link IjModule} form the supplied parameters.
   *
   * @param moduleBasePath the top-most directory the module is responsible for.
   * @param targetNodes set of nodes the module is to be created from.
   * @return nice shiny new module.
   */
  @SuppressWarnings(
      "rawtypes") // https://github.com/immutables/immutables/issues/548 requires us to use
  // TargetNode not TargetNode<?>
  IjModule createModule(
      Path moduleBasePath, ImmutableSet<TargetNode> targetNodes, Set<Path> excludes);
}
