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

package com.facebook.buck.android;

import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public class IdentityResourcesProvider implements FilteredResourcesProvider {

  private final ImmutableList<SourcePath> resDirectories;

  public IdentityResourcesProvider(ImmutableList<SourcePath> resDirectories) {
    this.resDirectories = resDirectories;
  }

  @Override
  public ImmutableList<SourcePath> getResDirectories() {
    return resDirectories;
  }

  @Override
  public ImmutableSet<SourcePath> getNonEnglishStringFiles() {
    return ImmutableSet.of();
  }
}
