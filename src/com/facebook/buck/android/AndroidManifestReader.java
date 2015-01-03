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

package com.facebook.buck.android;

import java.util.List;

/**
 * Allows querying an Android manifest file for various types of information.
 */
public interface AndroidManifestReader {

  /**
   * @return list of names (as they appear in the manifest) of activities that should appear in the
   * Android app drawer.
   */
  public List<String> getLauncherActivities();

  /**
   * @return the value of the package attribute to the manifest element.
   */
  public String getPackage();

  /**
   * @return the value of the versionCode attribute to the manifest element.
   */
  public String getVersionCode();
}
