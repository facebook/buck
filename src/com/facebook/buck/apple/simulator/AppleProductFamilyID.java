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

package com.facebook.buck.apple.simulator;

/**
 * Apple product family identifier (i.e., iPhone, iPad, Apple Watch, etc.).
 */
public enum AppleProductFamilyID {
    IPHONE(1),
    IPAD(2),
    APPLE_WATCH(4);

    private final int familyID;

    AppleProductFamilyID(int familyID) {
      this.familyID = familyID;
    }

    public int getFamilyID() {
      return familyID;
    }
}
