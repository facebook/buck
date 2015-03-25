/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.model;

public class BuckVersion {
  private static final String BUCK_VERSION_UID_KEY = "buck.version_uid";
  private static final String BUCK_VERSION_UID = System.getProperty(BUCK_VERSION_UID_KEY, "N/A");

  private BuckVersion() {
    // Not to be constructed.
  }

  public static String getVersion() {
    return BUCK_VERSION_UID;
  }
}
