/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRule;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;

public class AndroidPrebuiltAARCollection implements Iterable<AndroidPrebuiltAar> {

  private final Map<String, AndroidPrebuiltAar> rules = new HashMap<String, AndroidPrebuiltAar>();

  public AndroidPrebuiltAARCollection add(AndroidPrebuiltAar rule) {
    String name = computeKey(rule);
    rules.put(name, rule);
    return this;
  }

  @Nullable
  public AndroidPrebuiltAar getParentAAR(BuildRule dep) {
    String key = computeKey(dep);
    return rules.get(key);
  }

  public boolean contains(BuildRule dep) {
    String key = computeKey(dep);
    return rules.containsKey(key);
  }

  private static String computeKey(BuildRule dep) {
    String baseName = dep.getBuildTarget().getBasePath().toString();
    String shortName = dep.getBuildTarget().getShortName();
    return baseName + ":" + shortName;
  }

  public int size() {
    return rules.size();
  }

  @Override
  public Iterator<AndroidPrebuiltAar> iterator() {
    return rules.values().iterator();
  }
}
