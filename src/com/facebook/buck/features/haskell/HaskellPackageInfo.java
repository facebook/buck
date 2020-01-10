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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Identifying information for a {@link HaskellPackage}. */
@BuckStyleValue
abstract class HaskellPackageInfo implements AddsToRuleKey {

  public static HaskellPackageInfo of(String name, String version, String identifier) {
    return ImmutableHaskellPackageInfo.of(name, version, identifier);
  }

  @AddToRuleKey
  public abstract String getName();

  @AddToRuleKey
  public abstract String getVersion();

  @AddToRuleKey
  public abstract String getIdentifier();
}
