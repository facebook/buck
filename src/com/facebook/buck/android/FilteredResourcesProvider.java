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

import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public interface FilteredResourcesProvider {

  /**
   * @return The set of res/ directories that should be used to calculate the final R.java file.
   */
  public ImmutableSet<Path> getResDirectories();

  /**
   * @return The set of non-english {@code strings.xml} files identified by the resource filter.
   *     Only used if {@link #isStoreStringsAsAssets()} returns {@code true}.
   */
  public ImmutableSet<Path> getNonEnglishStringFiles();

  /**
   * @return Whether non-english strings need to be stored as assets.
   * TODO(natthu): Remove this method from the interface once string assets are created in a new
   *     buildable.
   */
  public boolean isStoreStringsAsAssets();
}
