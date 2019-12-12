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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.toolchain.HeaderVisibility;

/**
 * Utility class with methods working with {@link HeaderVisibility} in the context of Apple rules.
 */
public class AppleHeaderVisibilities {

  private AppleHeaderVisibilities() {
    // This class is not meant to be instantiated.
  }

  public static String toXcodeAttribute(HeaderVisibility headerVisibility) {
    switch (headerVisibility) {
      case PUBLIC:
        return "Public";
      case PRIVATE:
        return "Project";
    }
    throw new IllegalStateException("Invalid header visibility value: " + headerVisibility);
  }

  public static String getHeaderSymlinkTreeSuffix(HeaderVisibility headerVisibility) {
    switch (headerVisibility) {
      case PUBLIC:
        return "-pub";
      case PRIVATE:
        return "-priv";
    }
    throw new IllegalStateException("Invalid header visibility value: " + headerVisibility);
  }
}
