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

package com.facebook.buck.ide.intellij.aggregation;

import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * Represents a module during aggregation.
 *
 * <p>This module is used only for aggregation and discarded after. It contains a minimal
 * information necessary to create {@link IjModule}.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAggregationModule {

  @SuppressWarnings(
      "rawtypes") // https://github.com/immutables/immutables/issues/548 requires us to use TargetNode not TargetNode<?, ?>
  public abstract ImmutableSet<TargetNode> getTargets();

  /**
   * @return path to the top-most directory the module is responsible for. This is also where the
   *     corresponding .iml file is located.
   */
  public abstract Path getModuleBasePath();

  /**
   * Uniquely identifies the type of this module for aggregation purpose.
   *
   * <p>Modules with the same aggregation tag can be aggregated into one module.
   */
  public abstract String getAggregationTag();

  public abstract IjModuleType getModuleType();

  /** A set of paths that need to be excluded from the final {@link IjModule}. */
  public abstract ImmutableSet<Path> getExcludes();
}
