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

import com.facebook.buck.core.model.BuildTarget;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Map;

/**
 * A simple constraint solver that ignores all constraints and always picks the first listed version
 * for each versioned node.
 */
public class NaiveVersionSelector implements VersionSelector {

  @Override
  public ImmutableMap<BuildTarget, Version> resolve(
      BuildTarget root, ImmutableMap<BuildTarget, ImmutableSet<Version>> domain) {
    ImmutableMap.Builder<BuildTarget, Version> selectedVersions = ImmutableMap.builder();
    for (Map.Entry<BuildTarget, ImmutableSet<Version>> ent : domain.entrySet()) {
      selectedVersions.put(ent.getKey(), Iterables.get(ent.getValue(), 0));
    }
    return selectedVersions.build();
  }
}
