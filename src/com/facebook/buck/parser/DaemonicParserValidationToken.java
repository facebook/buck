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

package com.facebook.buck.parser;

/** A token used in cache access operation to verify cache is still valid. */
class DaemonicParserValidationToken {
  final int invalidationCount;

  DaemonicParserValidationToken(int invalidationCount) {
    this.invalidationCount = invalidationCount;
  }

  boolean isInvalid() {
    return invalidationCount == Integer.MAX_VALUE;
  }

  /** Token which is always invalid. */
  static DaemonicParserValidationToken invalid() {
    return new DaemonicParserValidationToken(Integer.MAX_VALUE);
  }

  @Override
  public String toString() {
    return "DaemonicParserValidationToken{" + "invalidationCount=" + invalidationCount + '}';
  }
}
