/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.rules.modern.Buildable;

/**
 * A simple holder for a Buildable/BuildTarget pair. Just used as a convenience for serialization as
 * the target is often needed for dealing with a Buildable.
 */
public class BuildableAndTarget implements AddsToRuleKey {
  @AddToRuleKey public final Buildable buildable;
  @AddToRuleKey public final BuildTarget target;

  public BuildableAndTarget(Buildable buildable, BuildTarget target) {
    this.buildable = buildable;
    this.target = target;
  }
}
