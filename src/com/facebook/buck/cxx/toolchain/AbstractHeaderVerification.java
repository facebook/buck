/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.immutables.value.Value;

/**
 * Defines how to handle headers that get included during the build but aren't explicitly tracked in
 * any build files.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractHeaderVerification implements RuleKeyAppendable {

  @Value.Parameter
  public abstract Mode getMode();

  /** @return a list of regexes which match headers which should be exempt from verification. */
  @Value.Parameter
  @Value.NaturalOrder
  protected abstract ImmutableSortedSet<String> getWhitelist();

  /**
   * @return a list of regexes which match headers from the platform SDK. The path for the platforms
   *     might depend on the disk layout. Therefore, we don't want that one to be included in the
   *     rule keys.
   */
  @Value.Parameter
  @Value.NaturalOrder
  protected abstract ImmutableSortedSet<String> getPlatformWhitelist();

  @Value.Derived
  protected Iterable<Pattern> getWhitelistPatterns() {
    return Stream.concat(getWhitelist().stream(), getPlatformWhitelist().stream())
        .map(Pattern::compile)
        .collect(ImmutableList.toImmutableList());
  }

  public static HeaderVerification of(Mode mode) {
    return HeaderVerification.builder().setMode(mode).build();
  }

  /** @return whether the given header has been whitelisted. */
  public boolean isWhitelisted(String header) {
    for (Pattern pattern : getWhitelistPatterns()) {
      if (pattern.matcher(header).matches()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("mode", getMode());
    if (getMode() != Mode.IGNORE) {
      sink.setReflectively("whitelist", getWhitelist());
    }
  }

  public HeaderVerification withPlatformWhitelist(Iterable<String> elements) {
    return HeaderVerification.builder().from(this).addAllPlatformWhitelist(elements).build();
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
