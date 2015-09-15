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

package com.facebook.buck.dotnet;

public enum  FrameworkVersion {
  NET35("v3.5"),
  NET40("v4.0"),
  NET45("v4.5"),
  NET46("v4.6"),
  ;

  private final String dirName;

  private FrameworkVersion(String dirName) {
    this.dirName = dirName;
  }

  public String getDirName() {
    return dirName;
  }
}
