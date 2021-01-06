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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.stream.Stream;
import org.immutables.value.Value;

/**
 * Defines how to handle headers that get included during the build but aren't explicitly tracked in
 * any build files.
 */
@BuckStyleValue
public abstract class HeaderVerification implements AddsToRuleKey {

  @AddToRuleKey
  public abstract Mode getMode();

  /** @return a list of regexes which match headers which should be exempt from verification. */
  @Value.NaturalOrder
  @AddToRuleKey
  public abstract ImmutableSortedSet<String> getWhitelist();

  /**
   * @return a list of regexes which match headers from the platform SDK. The path for the platforms
   *     might depend on the disk layout. Therefore, we don't want that one to be included in the
   *     rule keys.
   */
  @Value.NaturalOrder
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  public abstract ImmutableSortedSet<String> getPlatformWhitelist();

  @Value.Derived
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  protected ImmutableList<FasterPattern> getWhitelistPatterns() {
    return Stream.concat(getWhitelist().stream(), getPlatformWhitelist().stream())
        .map(FasterPattern::compile)
        .collect(ImmutableList.toImmutableList());
  }

  public static HeaderVerification of(Mode mode) {
    return of(mode, ImmutableSortedSet.of(), ImmutableSortedSet.of());
  }

  public static HeaderVerification of(
      HeaderVerification.Mode mode,
      ImmutableSortedSet<String> whitelist,
      ImmutableSortedSet<String> platformWhitelist) {
    return ImmutableHeaderVerification.of(mode, whitelist, platformWhitelist);
  }

  /** @return whether the given header has been whitelisted. */
  public boolean isWhitelisted(String header) {
    for (FasterPattern pattern : getWhitelistPatterns()) {
      if (pattern.matches(header)) {
        return true;
      }
    }
    return false;
  }

  public HeaderVerification withPlatformWhitelist(Iterable<String> elements) {
    return of(
        getMode(),
        getWhitelist(),
        ImmutableSortedSet.<String>naturalOrder()
            .addAll(getPlatformWhitelist())
            .addAll(elements)
            .build());
  }

  public enum Mode {

    /** Allow untracked headers. */
    IGNORE,

    /** Warn on untracked headers. */
    WARN,

    /** Error on untracked headers. */
    ERROR,
  }
}
