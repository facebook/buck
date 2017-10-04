/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util.zip;

public enum ZipCompressionLevel {
  MIN_COMPRESSION_LEVEL(0),
  DEFAULT_COMPRESSION_LEVEL(6),
  MAX_COMPRESSION_LEVEL(9),
  ;

  private final int value;

  ZipCompressionLevel(int value) {
    this.value = value;
  }

  public int getValue() {
    return value;
  }
}
