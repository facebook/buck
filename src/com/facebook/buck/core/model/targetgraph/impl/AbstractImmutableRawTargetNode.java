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

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.targetgraph.RawAttributes;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import org.immutables.value.Value;

/** Immutable implementation of {@link RawTargetNode}. */
@Value.Immutable(builder = false, copy = false, prehash = true)
@BuckStyleImmutable
public abstract class AbstractImmutableRawTargetNode implements RawTargetNode {
  @Override
  @Value.Parameter
  public abstract BuildTarget getBuildTarget();

  @Override
  @Value.Parameter
  public abstract RuleType getRuleType();

  @Override
  @Value.Parameter
  public abstract RawAttributes getAttributes();

  @Override
  @Value.Parameter
  public abstract ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  @Override
  @Value.Parameter
  public abstract ImmutableSet<VisibilityPattern> getWithinViewPatterns();

  @Override
  @Value.Lazy
  public HashCode getHashCode() {
    return HashCode.fromInt(hashCode());
  }
}
