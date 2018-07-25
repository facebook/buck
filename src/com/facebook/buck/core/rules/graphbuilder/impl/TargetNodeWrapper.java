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

package com.facebook.buck.core.rules.graphbuilder.impl;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.graphbuilder.BuildRuleKey;
import javax.annotation.concurrent.Immutable;

/**
 * An immutable wrapper around a {@link TargetNode} so that {@link TargetNode} can be used in {@link
 * BuildRuleKey}.
 *
 * <p>The {@code Value.Immutables} generation is unable to deal with generic types, such as {@code
 * TargetNode<?,?>}. This class wraps the generic to be a specific type
 */
@Immutable
class TargetNodeWrapper {

  private final TargetNode<?> targetNode;
  private final int hashCode;

  private TargetNodeWrapper(TargetNode<?> targetNode) {
    this.targetNode = targetNode;
    this.hashCode = targetNode.hashCode();
  }

  public static TargetNodeWrapper of(TargetNode<?> targetNode) {
    return new TargetNodeWrapper(targetNode);
  }

  public TargetNode<?> getTargetNode() {
    return targetNode;
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj instanceof TargetNodeWrapper) {
      return targetNode.equals(((TargetNodeWrapper) obj).targetNode);
    }
    return false;
  }
}
