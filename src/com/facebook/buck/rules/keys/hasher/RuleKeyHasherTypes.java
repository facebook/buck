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

package com.facebook.buck.rules.keys.hasher;

public class RuleKeyHasherTypes {

  // This is a helper class.
  private RuleKeyHasherTypes() {}

  // Key
  public static final byte KEY = 'k';
  // Java types
  public static final byte NULL = '0';
  public static final byte TRUE = 'y';
  public static final byte FALSE = 'n';
  public static final byte BYTE_ARRAY = 'a';
  public static final byte BYTE = 'b';
  public static final byte SHORT = 'h';
  public static final byte INTEGER = 'i';
  public static final byte LONG = 'l';
  public static final byte FLOAT = 'f';
  public static final byte DOUBLE = 'd';
  public static final byte STRING = 's';
  public static final byte PATTERN = 'p';
  // Buck types
  public static final byte SHA1 = 'H';
  public static final byte PATH = 'P';
  public static final byte ARCHIVE_MEMBER_PATH = 'A';
  public static final byte NON_HASHING_PATH = 'N';
  public static final byte SOURCE_ROOT = 'R';
  public static final byte RULE_KEY = 'K';
  public static final byte RULE_TYPE = 'Y';
  public static final byte TARGET = 'T';
  public static final byte TARGET_SOURCE_PATH = 'S';
  // Containers
  public static final byte CONTAINER = 'C';
  public static final byte WRAPPER = 'W';

  public static byte wrapperSubType(RuleKeyHasher.Wrapper wrapper) {
    switch (wrapper) {
      case SUPPLIER:
        return (byte) 'S';
      case OPTIONAL:
        return (byte) 'O';
      case OPTIONAL_INT:
        return (byte) 'I';
      case EITHER_LEFT:
        return (byte) 'L';
      case EITHER_RIGHT:
        return (byte) 'R';
      case BUILD_RULE:
        return (byte) 'B';
      case APPENDABLE:
        return (byte) 'A';
      default:
        throw new UnsupportedOperationException("Unrecognized wrapper type: " + wrapper);
    }
  }

  public static byte containerSubType(RuleKeyHasher.Container container) {
    switch (container) {
      case TUPLE:
        return (byte) '(';
      case LIST:
        return (byte) '[';
      case MAP:
        return (byte) '{';
      default:
        throw new UnsupportedOperationException("Unrecognized container type: " + container);
    }
  }
}
