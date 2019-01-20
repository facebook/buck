/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys.config.impl;

import java.util.Objects;

/** Provides the Buck binary hash key. */
public final class BuckBinaryHashProvider {
  private BuckBinaryHashProvider() {}

  private static String buckBinaryHash;

  /**
   * Returns Buck binary hash
   *
   * <p>The Buck binary hash key is generated during build time and reflects changes in Buck code
   * that can affect the content of build artifacts.
   */
  public static String getBuckBinaryHash() {
    if (buckBinaryHash == null) {
      buckBinaryHash = Objects.requireNonNull(System.getProperty("buck.binary_hash"));
    }
    return buckBinaryHash;
  }
}
