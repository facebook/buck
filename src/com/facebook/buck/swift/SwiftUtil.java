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

public class SwiftUtil {

  /** Utility class: do not instantiate. */
  private SwiftUtil() { }

  public static class Constants {

    /** Utility class: do not instantiate. */
    private Constants() { }

    static final String SWIFT_SUFFIX = "~Swift";
    static final String SWIFT_HEADER_SUFFIX = "-Swift";
    public static final String SWIFT_EXTENSION = ".swift";
  }

  static boolean isSwiftCompanionLibrary(String moduleName) {
    return moduleName.endsWith(Constants.SWIFT_SUFFIX);
  }

  static String toSwiftHeaderName(String moduleName) {
    if (isSwiftCompanionLibrary(moduleName)) {
      moduleName = moduleName.substring(0, moduleName.length() - Constants.SWIFT_SUFFIX.length());
    }
    return moduleName + Constants.SWIFT_HEADER_SUFFIX;
  }

  static String normalizeSwiftModuleName(String moduleName) {
    return moduleName.replaceAll("[^A-Za-z0-9]", "_");
  }
}
