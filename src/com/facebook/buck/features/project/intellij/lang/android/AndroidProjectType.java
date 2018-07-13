/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij.lang.android;

/**
 * Constants that define Android project type.
 *
 * <p>These constants are taken from <code>com.android.builder.model.AndroidProject</code>.
 *
 * <p>
 *
 * @since IntelliJ 2017.2
 */
public enum AndroidProjectType {
  APP(0),
  LIBRARY(1);

  private final int id;

  AndroidProjectType(int id) {
    this.id = id;
  }

  public int getId() {
    return id;
  }
}
