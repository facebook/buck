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

package com.facebook.buck.apple.toolchain;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.hash.HashCode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.immutables.value.Value;

/** Represents a identity used in code signing. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCodeSignIdentity implements AddsToRuleKey {
  private static final Pattern STRICT_HASH_PATTERN = Pattern.compile("(^[A-Fa-f0-9]{40}$)");

  /**
   * A pseudo-identity for ad hoc code signing.
   *
   * <p>See the <a
   * href="https://developer.apple.com/library/mac/documentation/Darwin/Reference/ManPages/man1/codesign.1.html">codesign
   * man page</a>.
   *
   * <p>Binaries signed with this identity will not be installable on real devices. This is only
   * intended for Buck unit tests.
   */
  public static final CodeSignIdentity AD_HOC =
      CodeSignIdentity.builder()
          .setFingerprint(Optional.empty())
          .setSubjectCommonName("Ad Hoc")
          .build();

  public static CodeSignIdentity ofAdhocSignedWithSubjectCommonName(String commonName) {
    return CodeSignIdentity.builder()
        .setFingerprint(Optional.empty())
        .setSubjectCommonName(commonName)
        .setShouldUseSubjectCommonNameToSign(true)
        .build();
  }

  /**
   * Returns the identity's certificate hash, defined to be unique for each identity.
   *
   * <p>If absent, this identity represents an ad-hoc signing identity.
   */
  @AddToRuleKey(stringify = true)
  public abstract Optional<HashCode> getFingerprint();

  /**
   * Returns the full name of the identity. e.g. "iPhone Developer: John Doe (ABCDE12345)"
   *
   * <p>Not guaranteed to be unique.
   */
  @AddToRuleKey(stringify = true)
  public abstract String getSubjectCommonName();

  /**
   * @return True if {@link AbstractCodeSignIdentity#getSubjectCommonName()} can be used to sign if
   *     {@link AbstractCodeSignIdentity#getFingerprint()} is empty.
   */
  @AddToRuleKey(stringify = true)
  @Value.Default
  public boolean shouldUseSubjectCommonNameToSign() {
    return false;
  }

  /** Convert a {@code String} into a fingerprint {@code HashCode} if it's in the correct format. */
  public static Optional<HashCode> toFingerprint(String identifier) {
    Matcher matcher = STRICT_HASH_PATTERN.matcher(identifier);
    if (matcher.matches()) {
      return Optional.of(HashCode.fromString(identifier.toLowerCase()));
    } else {
      return Optional.empty();
    }
  }
}
