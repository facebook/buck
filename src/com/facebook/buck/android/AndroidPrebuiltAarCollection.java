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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Preconditions;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class AndroidPrebuiltAarCollection implements Iterable<AndroidPrebuiltAar> {

  private final Map<BuildTarget, AndroidPrebuiltAar> rules = new HashMap<>();

  public AndroidPrebuiltAarCollection add(AndroidPrebuiltAar rule) {
    rules.put(rule.getBuildTarget(), rule);
    return this;
  }

  public AndroidPrebuiltAar getParentAar(BuildRule dep) {
    return Preconditions.checkNotNull(rules.get(dep.getBuildTarget()));
  }

  public boolean contains(BuildRule dep) {
    return rules.containsKey(dep.getBuildTarget());
  }

  public int size() {
    return rules.size();
  }

  @Override
  public Iterator<AndroidPrebuiltAar> iterator() {
    return rules.values().iterator();
  }
}
