/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.versions;

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class FixedVersionSelector implements VersionSelector {

  private final ImmutableMap<BuildTarget, ImmutableMap<BuildTarget, Version>> selections;

  public FixedVersionSelector(
      ImmutableMap<BuildTarget, ImmutableMap<BuildTarget, Version>> selections) {
    this.selections = selections;
  }

  @Override
  public ImmutableMap<BuildTarget, Version> resolve(
      BuildTarget root, ImmutableMap<BuildTarget, ImmutableSet<Version>> domain) {
    return Preconditions.checkNotNull(selections.get(root));
  }
}
