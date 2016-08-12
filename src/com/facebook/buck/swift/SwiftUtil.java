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

package com.facebook.buck.swift;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;

public class SwiftUtil {

  /** Utility class: do not instantiate. */
  private SwiftUtil() { }

  public static class Constants {

    /** Utility class: do not instantiate. */
    private Constants() { }

    static final Flavor SWIFT_FLAVOR = ImmutableFlavor.of("swift");
    static final String SWIFT_HEADER_SUFFIX = "-Swift";
    static final String SWIFT_MAIN_FILENAME = "main.swift";
    public static final String SWIFT_EXTENSION = ".swift";
  }

  static String toSwiftHeaderName(String moduleName) {
    return moduleName + Constants.SWIFT_HEADER_SUFFIX;
  }

  static String normalizeSwiftModuleName(String moduleName) {
    return moduleName.replaceAll("[^A-Za-z0-9]", "_");
  }
}
