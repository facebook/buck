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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.coercer.XcodeRuleConfiguration;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

public class AppleLibraryBuilder extends AbstractNodeBuilder<AppleNativeTargetDescriptionArg> {

  protected AppleLibraryBuilder(BuildTarget target) {
    super(new AppleLibraryDescription(new AppleConfig(new FakeBuckConfig())), target);
  }

  public static AppleLibraryBuilder createBuilder(BuildTarget target) {
    return new AppleLibraryBuilder(target);
  }

  public AppleLibraryBuilder setConfigs(
      Optional<ImmutableSortedMap<String, XcodeRuleConfiguration>> configs) {
    arg.configs = configs;
    return this;
  }

  public AppleLibraryBuilder setDeps(Optional<ImmutableSortedSet<BuildTarget>> deps) {
    arg.deps = deps;
    return this;
  }

}
