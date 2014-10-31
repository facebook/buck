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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.XcodeRuleConfiguration;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

public class AppleBinaryBuilder extends AbstractNodeBuilder<AppleNativeTargetDescriptionArg> {

  protected AppleBinaryBuilder(BuildTarget target) {
    super(new AppleBinaryDescription(new AppleConfig(new FakeBuckConfig())), target);
  }

  public static AppleBinaryBuilder createBuilder(BuildTarget target) {
    return new AppleBinaryBuilder(target);
  }

  public AppleBinaryBuilder setConfigs(
      Optional<ImmutableSortedMap<String, XcodeRuleConfiguration>> configs) {
    arg.configs = configs;
    return this;
  }

  public AppleBinaryBuilder setSrcs(Optional<ImmutableList<AppleSource>> srcs) {
    arg.srcs = srcs;
    return this;
  }

  public AppleBinaryBuilder setFrameworks(Optional<ImmutableSortedSet<String>> frameworks) {
    arg.frameworks = frameworks;
    return this;
  }

  public AppleBinaryBuilder setDeps(Optional<ImmutableSortedSet<BuildTarget>> deps) {
    arg.deps = deps;
    return this;
  }

  public AppleBinaryBuilder setGid(Optional<String> gid) {
    arg.gid = gid;
    return this;
  }

  public AppleBinaryBuilder setHeaderPathPrefix(Optional<String> headerPathPrefix) {
    arg.headerPathPrefix = headerPathPrefix;
    return this;
  }

  public AppleBinaryBuilder setUseBuckHeaderMaps(Optional<Boolean> useBuckHeaderMaps) {
    arg.useBuckHeaderMaps = useBuckHeaderMaps;
    return this;
  }

  public AppleBinaryBuilder setPrefixHeader(Optional<SourcePath> prefixHeader) {
    arg.prefixHeader = prefixHeader;
    return this;
  }

}
