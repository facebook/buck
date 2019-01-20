/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.cache.impl;

/** Describes what operations the {@link ParserCache} allows. */
public enum ParserCacheAccessMode {
  // No access allowed for the cache.
  NONE,
  // Only reads allowed.
  READONLY,
  // Only writes allowed.
  WRITEONLY,
  // Allows reads and writes.
  READWRITE,
  ;

  /**
   * Check for writability of the cache.
   *
   * @return true if the tested {@code mode} is associated with a writeable cache. Otherwise false.
   */
  public boolean isWritable() {
    return (this == WRITEONLY) || (this == READWRITE);
  }

  /**
   * Check for readability of the cache.
   *
   * @return true if the tested {@code mode} is associated with a readable cache. Otherwise false.
   */
  public boolean isReadable() {
    return (this == READONLY) || (this == READWRITE);
  }

  /**
   * Check for the cache. being readable and writeable.
   *
   * @return true if the tested {@code mode} is associated with a readable and writable cache.
   *     Otherwise false.
   */
  public boolean isReadWriteMode() {
    return (this == READWRITE);
  }
}
