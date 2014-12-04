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
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

public abstract class AbstractAppleNativeTargetBuilder<
    ARG extends AppleNativeTargetDescriptionArg,
    BUILDER extends AbstractAppleNativeTargetBuilder<ARG, BUILDER>>
    extends AbstractNodeBuilder<ARG> {

  public AbstractAppleNativeTargetBuilder(
      Description<ARG> description,
      BuildTarget target) {
    super(description, target);
  }

  public BUILDER setConfigs(
      Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs) {
    arg.configs = configs;
    return getThis();
  }

  public BUILDER setSrcs(Optional<ImmutableList<AppleSource>> srcs) {
    arg.srcs = srcs;
    return getThis();
  }

  public BUILDER setFrameworks(Optional<ImmutableSortedSet<String>> frameworks) {
    arg.frameworks = frameworks;
    return getThis();
  }

  public BUILDER setDeps(Optional<ImmutableSortedSet<BuildTarget>> deps) {
    arg.deps = deps;
    return getThis();
  }

  public BUILDER setGid(Optional<String> gid) {
    arg.gid = gid;
    return getThis();
  }

  public BUILDER setHeaderPathPrefix(Optional<String> headerPathPrefix) {
    arg.headerPathPrefix = headerPathPrefix;
    return getThis();
  }

  public BUILDER setUseBuckHeaderMaps(Optional<Boolean> useBuckHeaderMaps) {
    arg.useBuckHeaderMaps = useBuckHeaderMaps;
    return getThis();
  }

  public BUILDER setPrefixHeader(Optional<SourcePath> prefixHeader) {
    arg.prefixHeader = prefixHeader;
    return getThis();
  }

  public BUILDER setTests(Optional<ImmutableSortedSet<BuildTarget>> tests) {
    arg.tests = tests;
    return getThis();
  }

  protected abstract BUILDER getThis();
}
