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

/**
 * Known bundle extensions that have special handling.
 */
public enum AppleBundleExtension {
  APP,
  FRAMEWORK,
  APPEX,
  PLUGIN,
  BUNDLE,
  OCTEST,
  XCTEST;

  public String toFileExtension() {
    switch (this) {
      case APP:
        return "app";
      case FRAMEWORK:
        return "framework";
      case APPEX:
        return "appex";
      case PLUGIN:
        return "plugin";
      case BUNDLE:
        return "bundle";
      case OCTEST:
        return "octest";
      case XCTEST:
        return "xctest";
      default:
        throw new IllegalStateException("Invalid bundle extension value: " + this.toString());
    }
  }
}
