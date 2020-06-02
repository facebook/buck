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

package com.facebook.buck.features.supermodule;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

/** Information output for each target of interest by {@code SupermoduleTargetGraph} */
@BuckStyleValue
public abstract class TargetInfo implements AddsToRuleKey {

  @Value.NaturalOrder
  @AddToRuleKey
  public abstract ImmutableSortedSet<String> getDeps();

  @Value.NaturalOrder
  @AddToRuleKey
  public abstract ImmutableSortedSet<String> getLabels();

  public static TargetInfo of(ImmutableSortedSet<String> deps, ImmutableSortedSet<String> labels) {
    return ImmutableTargetInfo.ofImpl(deps, labels);
  }
}
