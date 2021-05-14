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

package com.facebook.buck.android.apkmodule;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class APKModule implements Comparable<APKModule>, AddsToRuleKey {

  public static APKModule of(String name, boolean hasResources, boolean hasManifest) {
    return ImmutableAPKModule.of(name, hasResources, hasManifest);
  }

  @AddToRuleKey
  public abstract String getName();

  @AddToRuleKey
  public abstract boolean hasResources();

  @AddToRuleKey
  public abstract boolean hasManifest();

  @Value.Derived
  public boolean isRootModule() {
    return getName().equals(APKModuleGraph.ROOT_APKMODULE_NAME);
  }

  @Value.Derived
  public String getCanaryClassName() {
    if (isRootModule()) {
      return "secondary";
    } else {
      return String.format("store%04x", getName().hashCode() & 0xFFFF);
    }
  }

  @Override
  public int compareTo(APKModule o) {
    if (this == o) {
      return 0;
    }

    return getName().compareTo(o.getName());
  }
}
