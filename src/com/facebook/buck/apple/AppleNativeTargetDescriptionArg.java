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

import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Arguments common to Apple targets.
 */
@SuppressFieldNotInitialized
public class AppleNativeTargetDescriptionArg implements HasTests {
  public Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs;
  public Optional<ImmutableList<SourceWithFlags>> srcs;
  public Optional<ImmutableList<SourcePath>> extraXcodeSources;
  public Optional<SourceList> headers;
  public Optional<SourceList> exportedHeaders;
  public Optional<ImmutableSortedSet<FrameworkPath>> frameworks;
  public Optional<ImmutableSortedSet<BuildTarget>> deps;
  public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
  public Optional<String> gid;
  public Optional<String> headerPathPrefix;
  public Optional<Boolean> useBuckHeaderMaps;
  public Optional<SourcePath> prefixHeader;
  @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;
  public Optional<ImmutableList<String>> preprocessorFlags;
  public Optional<ImmutableList<String>> exportedPreprocessorFlags;
  public Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> langPreprocessorFlags;
  public Optional<ImmutableList<String>> compilerFlags;
  public Optional<ImmutableList<String>> linkerFlags;
  public Optional<ImmutableList<String>> exportedLinkerFlags;

  public boolean getUseBuckHeaderMaps() {
    return useBuckHeaderMaps.or(true);
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests.get();
  }
}
