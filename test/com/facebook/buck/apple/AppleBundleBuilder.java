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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.AppleBundleDestination;
import com.facebook.buck.rules.coercer.Either;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

public class AppleBundleBuilder
    extends AbstractNodeBuilder<AppleBundleDescription.Arg> {

  protected AppleBundleBuilder(BuildTarget target) {
    super(new AppleBundleDescription(), target);
  }

  public static AppleBundleBuilder createBuilder(BuildTarget target) {
    return new AppleBundleBuilder(target);
  }

  public AppleBundleBuilder setExtension(Either<AppleBundleExtension, String> extension) {
    arg.extension = extension;
    return this;
  }

  public AppleBundleBuilder setXcodeProductType(Optional<String> xcodeProductType) {
    arg.xcodeProductType = xcodeProductType;
    return this;
  }

  public AppleBundleBuilder setBinary(BuildTarget binary) {
    arg.binary = binary;
    return this;
  }

  public AppleBundleBuilder setInfoPlist(Optional<SourcePath> infoPlist) {
    arg.infoPlist = infoPlist;
    return this;
  }

  public AppleBundleBuilder setHeaders(Optional<ImmutableMap<String, SourcePath>> headers) {
    arg.headers = headers;
    return this;
  }

  public AppleBundleBuilder setFiles(
      Optional<ImmutableMap<AppleBundleDestination, SourcePath>> files) {
    arg.files = files;
    return this;
  }

  public AppleBundleBuilder setDeps(Optional<ImmutableSortedSet<BuildTarget>> deps) {
    arg.deps = deps;
    return this;
  }

  public AppleBundleBuilder setTests(Optional<ImmutableSortedSet<BuildTarget>> tests) {
    arg.tests = tests;
    return this;
  }

}
