/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.files;

import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import java.nio.file.Path;

/** Transformation key that has info about directory listing computation */
@BuckStylePrehashedValue
public abstract class DirectoryListKey implements ComputeKey<DirectoryList> {

  public static final ComputationIdentifier<DirectoryList> IDENTIFIER =
      ClassBasedComputationIdentifier.of(DirectoryListKey.class, DirectoryList.class);

  /** Path of the directory to list */
  public abstract Path getPath();

  @Override
  public ComputationIdentifier<DirectoryList> getIdentifier() {
    return IDENTIFIER;
  }

  public static DirectoryListKey of(Path path) {
    return ImmutableDirectoryListKey.of(path);
  }

  // sergeyb: do we need cell identifier?
  // sergeyb: do we need filesystem identifier?
}
