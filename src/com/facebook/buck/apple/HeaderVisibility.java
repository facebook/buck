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

package com.facebook.buck.apple;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Ascii;
import com.google.common.base.Optional;

import java.util.List;

/**
 * The visibility of a header file.
 */
public enum HeaderVisibility {
    /** Visible to all dependencies. */
    PUBLIC,
    /** Visible to other dependencies inside the project. */
    PROJECT;

  public static HeaderVisibility fromString(String s) {
    switch (Ascii.toLowerCase(s)) {
      case "public":
        return HeaderVisibility.PUBLIC;
      case "project":
        return HeaderVisibility.PROJECT;
      default:
        throw new HumanReadableException("Invalid header visibility value %s.", s);
    }
  }

  public static Optional<HeaderVisibility> fromFlags(List<String> flags) {
    if (flags == null || flags.isEmpty()) {
      return Optional.absent();
    }
    if (flags.size() != 1) {
      throw new HumanReadableException(
          "Header file has more than one per-file flag: %s",
          flags);
    }
    String headerFlag = flags.get(0);
    return Optional.of(HeaderVisibility.fromString(headerFlag));
  }

  public String toXcodeAttribute() {
    switch (this) {
      case PUBLIC:
        return "Public";
      case PROJECT:
        return "Project";
      default:
        throw new IllegalStateException("Invalid header visibility value: " + this.toString());
    }
  }
}
