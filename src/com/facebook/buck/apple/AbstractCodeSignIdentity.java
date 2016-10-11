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
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.hash.HashCode;

import org.immutables.value.Value;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Represents a identity used in code signing.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCodeSignIdentity implements RuleKeyAppendable {
  private static final Pattern STRICT_HASH_PATTERN = Pattern.compile("(^[A-Fa-f0-9]{40}$)");

  /** A pseudo-identity for ad hoc code signing.
   *
   * See the <a href="https://developer.apple.com/library/mac/documentation/Darwin/Reference/ManPages/man1/codesign.1.html">codesign man page</a>.
   *
   * Binaries signed with this identity will not be installable on real devices.  This is only
   * intended for Buck unit tests.
   */
  public static final CodeSignIdentity AD_HOC = CodeSignIdentity.builder()
      .setFingerprint(Optional.absent()).setSubjectCommonName("Ad Hoc").build();

  /**
   * Returns the identity's certificate hash, defined to be unique for each identity.
   *
   * If absent, this identity represents an ad-hoc signing identity.
   */
  public abstract Optional<HashCode> getFingerprint();

  /** Returns the full name of the identity.
   * e.g. "iPhone Developer: John Doe (ABCDE12345)"
   *
   * Not guaranteed to be unique.
   */
  public abstract String getSubjectCommonName();

  /**
   * Convert a {@code String} into a fingerprint {@code HashCode} if it's in the correct format.
   */
  public static Optional<HashCode> toFingerprint(String identifier) {
    Matcher matcher = STRICT_HASH_PATTERN.matcher(identifier);
    if (matcher.matches()) {
      return Optional.of(HashCode.fromString(identifier.toLowerCase()));
    } else {
      return Optional.absent();
    }
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively(
        "code-sign-identity",
        getFingerprint().transform(Functions.toStringFunction()));
  }
}
