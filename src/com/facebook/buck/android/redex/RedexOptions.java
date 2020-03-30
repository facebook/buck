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

package com.facebook.buck.android.redex;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

@BuckStyleValue
public abstract class RedexOptions implements AddsToRuleKey {
  @AddToRuleKey
  public abstract Tool getRedex();

  @AddToRuleKey
  public abstract Optional<SourcePath> getRedexConfig();

  @AddToRuleKey
  public abstract ImmutableList<Arg> getRedexExtraArgs();

  public static RedexOptions of(
      Tool redex, Optional<SourcePath> redexConfig, ImmutableList<Arg> redexExtraArgs) {
    return ImmutableRedexOptions.of(redex, redexConfig, redexExtraArgs);
  }
}
