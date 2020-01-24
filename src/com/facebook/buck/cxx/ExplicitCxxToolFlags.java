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

package com.facebook.buck.cxx;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;

/** {@code CxxToolFlags} implementation where the flags are stored explicitly as lists. */
@BuckStyleValueWithBuilder
public abstract class ExplicitCxxToolFlags extends CxxToolFlags {

  static final ExplicitCxxToolFlags EMPTY = ImmutableExplicitCxxToolFlags.builder().build();

  @Override
  @AddToRuleKey
  public abstract ImmutableList<Arg> getPlatformFlags();

  @Override
  @AddToRuleKey
  public abstract ImmutableList<Arg> getRuleFlags();

  public static void addCxxToolFlags(Builder builder, CxxToolFlags flags) {
    builder.addAllPlatformFlags(flags.getPlatformFlags());
    builder.addAllRuleFlags(flags.getRuleFlags());
  }

  static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableExplicitCxxToolFlags.Builder {

    @Override
    public ExplicitCxxToolFlags build() {
      ExplicitCxxToolFlags instance = super.build();
      if (instance.equals(EMPTY)) {
        return EMPTY;
      }
      return instance;
    }
  }
}
