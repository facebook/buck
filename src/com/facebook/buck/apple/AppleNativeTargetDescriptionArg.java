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

import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.SourcePath;
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
public class AppleNativeTargetDescriptionArg extends CxxLibraryDescription.Arg implements HasTests {
  public Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs;
  public Optional<ImmutableList<SourcePath>> extraXcodeSources;

  public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
  public Optional<String> headerPathPrefix;
}
