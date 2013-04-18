/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

public enum TriState {
  TRUE,
  FALSE,
  UNSPECIFIED,
  ;

  /** @return whether this value is set; that is, whether it is TRUE or FALSE */
  public boolean isSet() {
    return this != UNSPECIFIED;
  }

  public boolean asBoolean() {
    switch (this) {
    case TRUE:
      return true;
    case FALSE:
      return false;
    case UNSPECIFIED:
      throw new IllegalStateException("No boolean equivalent for UNSPECIFIED");
    default:
      throw new IllegalStateException("Unrecognzied TriState value: " + this);
    }
  }

  public static TriState forBooleanValue(boolean value) {
    return value ? TRUE : FALSE;
  }
}
