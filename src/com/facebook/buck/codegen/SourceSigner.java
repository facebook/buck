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

package com.facebook.buck.codegen;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Designates machine-generated code so tools can distinguish it from
 * human-generated code, and prevent manual edits of machine-generated code by
 * embedding a simple checksum in generated source files.
 */
public class SourceSigner {

  public enum SignatureStatus {
      /**
       * No signature is present.
       */
      UNSIGNED,
      /**
       * The signature is present but does not match the contents of the source file.
       */
      INVALID,
      /**
       * The signature is present and matches the contents of the source file.
       */
      OK,
  }

  // Note that we separate '@' from 'generated' so this file isn't itself marked as generated.
  @VisibleForTesting
  static final String GENERATED = "@" + "generated";
  @VisibleForTesting
  static final String PLACEHOLDER = "<<SignedSource::*O*zOeWoEQle#+L!plEphiEmie@IsG>>";

  /**
   * When generating a new file to be signed, include this placeholder somewhere inside
   * the contents of the file, then pass it to {@link sign(String)} to sign the contents.
   */
  public static final String SIGNED_SOURCE_PLACEHOLDER = GENERATED + " " + PLACEHOLDER;

  private static final String SIGNATURE_REGEX = "SignedSource<<(?<signature>[0-9a-f]{32})>>";
  private static final String SIGNATURE_FORMAT = "SignedSource<<%s>>";
  private static final Pattern SIGNATURE_PATTERN = Pattern.compile(
      GENERATED + " " + SIGNATURE_REGEX);
  private static final Pattern SIGNATURE_OR_PLACEHOLDER_PATTERN = Pattern.compile(
      SIGNATURE_REGEX + "|" + Pattern.quote(PLACEHOLDER));

  /**
   * Given the contents of a source file, checks if a signature is present,
   * and if so, checks if the signature is valid.
   */
  public static final SignatureStatus getSignatureStatus(String source) {
    Matcher matcher = SIGNATURE_PATTERN.matcher(source);
    if (!matcher.find()) {
      return SignatureStatus.UNSIGNED;
    }

    if (matcher.groupCount() == 1) {
      String sourceSignature = matcher.group("signature");
      String sourceSignatureReplacedWithToken = matcher.replaceFirst(SIGNED_SOURCE_PLACEHOLDER);
      String expectedSignature = Hashing.md5()
          .hashString(sourceSignatureReplacedWithToken, Charsets.UTF_8)
          .toString();
      if (sourceSignature.equals(expectedSignature)) {
        return SignatureStatus.OK;
      }
    }

    return SignatureStatus.INVALID;
  }

  /**
   * Given the contents of a source file containing 0 or more existing signatures
   * or signature placeholders, calculates an updated signature and updates
   * the signatures in the source file.
   *
   * If the source file contains at least one signature, returns an Optional
   * containing the updated source file. Otherwise, returns Optional.absent().
   */
  public static final Optional<String> sign(String source) {
    Matcher matcher = SIGNATURE_OR_PLACEHOLDER_PATTERN.matcher(source);
    Hasher md5Hasher = Hashing.md5().newHasher();
    int pos = 0;
    while (matcher.find()) {
      if (matcher.groupCount() == 1) {
        // Update the digest up to the matched md5, but then hash the
        // placeholder token instead of the md5.
        md5Hasher.putString(source.substring(pos, matcher.start()), Charsets.UTF_8);
        md5Hasher.putString(PLACEHOLDER, Charsets.UTF_8);
      } else {
        md5Hasher.putString(source.substring(pos, matcher.end()), Charsets.UTF_8);
      }
      pos = matcher.end();
    }

    if (pos > 0) {
      md5Hasher.putString(source.substring(pos), Charsets.UTF_8);
      matcher.reset();
      String newSignature = formatSignature(md5Hasher.hash().toString());
      return Optional.of(matcher.replaceAll(newSignature));
    } else {
      return Optional.absent();
    }
  }

  @VisibleForTesting
  static String formatSignature(String signature) {
    return String.format(SIGNATURE_FORMAT, signature);
  }
}
