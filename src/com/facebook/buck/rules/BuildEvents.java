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

package com.facebook.buck.rules;

public class BuildEvents {

  private BuildEvents() {
    // Utility class.
  }

  public static BuildEvent started() {
    return new BuildStarted();
  }

  public static BuildEvent finished(int exitCode) {
    return new BuildFinished(exitCode);
  }

  public static BuildEvent started(BuildRule rule) {
    return new BuildRuleStarted(rule);
  }

  public static BuildEvent finished(BuildRule rule, BuildRuleStatus success, CacheResult isCacheHit) {
    return new BuildRuleFinished(rule, success, isCacheHit);
  }
}
