/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.SourcePath;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

@SuppressFieldNotInitialized
public class ReactNativeLibraryArgs extends AbstractDescriptionArg {
  public ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  public SourcePath entryPath;
  public String bundleName;
  public Optional<String> packagerFlags;

  public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
}
