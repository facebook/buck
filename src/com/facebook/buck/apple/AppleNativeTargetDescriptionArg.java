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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.XcodeRuleConfiguration;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Arguments common to {@link com.facebook.buck.apple.AbstractAppleNativeTargetBuildRule} subclasses
 */
@SuppressFieldNotInitialized
public class AppleNativeTargetDescriptionArg {
  public Optional<ImmutableSortedMap<String, XcodeRuleConfiguration>> configs;
  public Optional<ImmutableList<AppleSource>> srcs;
  public Optional<ImmutableSortedSet<String>> frameworks;
  public Optional<ImmutableSortedSet<BuildTarget>> deps;
  public Optional<String> gid;
  public Optional<String> headerPathPrefix;
  public Optional<Boolean> useBuckHeaderMaps;
  public Optional<SourcePath> prefixHeader;
}
