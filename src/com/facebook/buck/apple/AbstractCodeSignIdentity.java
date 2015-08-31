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

package com.facebook.buck.apple;

import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;

import org.immutables.value.Value;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Represents a identity used in code signing.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCodeSignIdentity implements RuleKeyAppendable {
  private static final Pattern STRICT_HASH_PATTERN = Pattern.compile("(^[A-F0-9]{40}$)");

  /** A pseudo-identity for ad hoc code signing.
   *
   * See the <a href="https://developer.apple.com/library/mac/documentation/Darwin/Reference/ManPages/man1/codesign.1.html">codesign man page</a>.
   *
   * Binaries signed with this identity will not be installable on real devices.  This is only
   * intended for Buck unit tests.
   */
  public static final CodeSignIdentity AD_HOC = CodeSignIdentity.builder()
      .setHash("-").setFullName("Ad Hoc").build();

  /** Returns the identity's certificate hash, defined to be unique for each identity.
   */
  public abstract String getHash();

  /** Returns the full name of the identity.
   * e.g. "iPhone Developer: John Doe (ABCDE12345)"
   *
   * Not guaranteed to be unique.
   */
  public abstract String getFullName();

  /** Whether this is a hash identifier (or the special ad-hoc '-') or not.
   */
  public static boolean isHash(String identifier) {
    Matcher matcher = STRICT_HASH_PATTERN.matcher(identifier);
    return matcher.find() || identifier.equals("-");
  }

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(isHash(getHash()));
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder.setReflectively("code-sign-identity", getHash());
  }
}
