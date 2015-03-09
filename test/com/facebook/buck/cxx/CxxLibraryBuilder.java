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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

public class CxxLibraryBuilder extends AbstractCxxSourceBuilder<CxxLibraryDescription.Arg> {

  public CxxLibraryBuilder(
      BuildTarget target,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new CxxLibraryDescription(
            cxxBuckConfig,
            cxxPlatforms,
            CxxSourceRuleFactory.Strategy.SEPARATE_PREPROCESS_AND_COMPILE),
        target);
  }

  public CxxLibraryBuilder(BuildTarget target) {
    this(target, createDefaultConfig(), createDefaultPlatforms());
  }

  public CxxLibraryBuilder setExportedHeaders(ImmutableList<SourcePath> headers)  {
    arg.exportedHeaders =
        Optional.of(
            Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(headers));
    return this;
  }

  public CxxLibraryBuilder setExportedHeaders(ImmutableMap<String, SourcePath> headers)  {
    arg.exportedHeaders =
        Optional.of(
            Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofRight(headers));
    return this;
  }

  public CxxLibraryBuilder setExportedHeaders(
      Either<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>> headers)  {
    arg.exportedHeaders = Optional.of(headers);
    return this;
  }


  public CxxLibraryBuilder setSoname(String soname) {
    arg.soname = Optional.of(soname);
    return this;
  }

  public CxxLibraryBuilder setLinkWhole(boolean linkWhole) {
    arg.linkWhole = Optional.of(linkWhole);
    return this;
  }

  public CxxLibraryBuilder setTests(ImmutableSortedSet<BuildTarget> tests) {
    arg.tests = Optional.of(tests);
    return this;
  }
}
