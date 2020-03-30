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

package com.facebook.buck.testutil.integration;

/** Helper to replace buck-out configuration hashes by a placeholder. */
public class BuckOutConfigHashPlaceholder {
  private static final String CONFIG_HASH_PLACEHOLDER = "<CONFIG_HASH>";
  private static final String CONFIG_HASH_PLACEHOLDER_WITH_TRAILING_SLASH =
      CONFIG_HASH_PLACEHOLDER + "/";

  /**
   * @param contents string. Usually contents from a file.
   * @return The contents string with **all** the buck-out config hashes replaced by a placeholder
   *     buck-out/gen/0a64e5aeadabd163fec103610dad77c1/folly/optional/libfolly_optional.dylib
   *     becomes buck-out/gen/<CONFIG_HASH>/folly/optional/libfolly_optional.dylib
   */
  public static String replaceHashByPlaceholder(String contents) {
    return contents.replaceAll(
        "(?<=buck-out/(gen|bin|annotation)/)[a-f0-9]{8}/",
        CONFIG_HASH_PLACEHOLDER_WITH_TRAILING_SLASH);
  }

  // TODO(gabrielrc): Remove "removePlaceholder" part once with land config hashes changes.
  public static String removeHash(String content) {
    return removePlaceholder(replaceHashByPlaceholder(content));
  }

  // TODO(gabrielrc): Remove this method once we land the config hashes changes.
  public static String removePlaceholder(String contents) {
    return contents.replaceAll(CONFIG_HASH_PLACEHOLDER_WITH_TRAILING_SLASH, "");
  }
}
