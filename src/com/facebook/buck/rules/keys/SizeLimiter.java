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

package com.facebook.buck.rules.keys;

/** A class that keeps track of size and throws an exception if the size limit is exceeded. */
public class SizeLimiter {

  public static class SizeLimitException extends RuntimeException {}

  private final long sizeLimit;
  private long currentSize;

  SizeLimiter(long sizeLimit) {
    this.sizeLimit = sizeLimit;
  }

  public void add(long size) {
    currentSize += size;
    if (currentSize > sizeLimit) {
      throw new SizeLimitException();
    }
  }
}
