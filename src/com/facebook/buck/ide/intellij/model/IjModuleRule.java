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
package com.facebook.buck.ide.intellij.model;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.ide.intellij.ModuleBuildContext;
import com.facebook.buck.ide.intellij.aggregation.AggregationContext;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;

/**
 * Rule describing which aspects of the supplied {@link TargetNode} to transfer to the {@link
 * IjModule} being constructed.
 *
 * @param <T> TargetNode Description Arg type.
 */
public interface IjModuleRule<T extends CommonDescriptionArg> {
  Class<? extends Description<?>> getDescriptionClass();

  void apply(TargetNode<T, ?> targetNode, ModuleBuildContext context);

  IjModuleType detectModuleType(TargetNode<T, ?> targetNode);

  void applyDuringAggregation(AggregationContext context, TargetNode<T, ?> targetNode);
}
