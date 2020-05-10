/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.select;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

public class TestSelectableResolver implements SelectableResolver {

  private final ImmutableMap<BuildTarget, Selectable> selectables;

  public TestSelectableResolver(ImmutableMap<BuildTarget, Selectable> selectables) {
    this.selectables = selectables;
  }

  public TestSelectableResolver() {
    this(ImmutableMap.of());
  }

  @Override
  public Selectable getSelectable(BuildTarget target, DependencyStack dependencyStack) {
    return Preconditions.checkNotNull(selectables.get(target), "selectable not found: " + target);
  }
}
