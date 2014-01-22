/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode.xcconfig;

import java.util.Objects;

/**
 * A condition applying to an entry in a xcconfig file
 */
public class Condition implements Comparable<Condition> {
  public final String key;
  public final String value;
  public final boolean isPrefix;

  public Condition(String key, String value, boolean prefix) {
    this.key = key;
    this.value = value;
    isPrefix = prefix;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Condition)) {
      return false;
    }

    Condition that = (Condition) other;
    return Objects.equals(this.isPrefix, that.isPrefix)
        && Objects.equals(this.key, that.key)
        && Objects.equals(this.value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, value, isPrefix);
  }

  @Override
  public int compareTo(Condition other) {
    int keyComp = key.compareTo(other.key);
    if (keyComp == 0) {
      int valueComp = value.compareTo(other.value);
      if (valueComp == 0) {
        return isPrefix == other.isPrefix ? 0 : (isPrefix == true ? 1 : -1);
      }
      return valueComp;
    }
    return keyComp;
  }

  @Override
  public String toString() {
    return key + "=" + value + (isPrefix ? "*" : "");
  }

}
