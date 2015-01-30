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

package com.facebook.buck.apple;

import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;

import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;

class Matchers {
  private Matchers() {}

  public static IsTargetWithName isTargetWithName(String name) {
    return new IsTargetWithName(org.hamcrest.Matchers.equalTo(name));
  }

  private static class IsTargetWithName extends FeatureMatcher<PBXTarget, String> {
    private IsTargetWithName(Matcher<? super String> subMatcher) {
      super(subMatcher, "target name", "IsTargetWithName");
    }

    @Override
    protected String featureValueOf(PBXTarget pbxTarget) {
      return pbxTarget.getName();
    }
  }
}
