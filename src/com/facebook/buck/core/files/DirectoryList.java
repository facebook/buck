/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.files;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Has information about files and folders at some directory */
@Value.Immutable(builder = false, copy = false)
public abstract class DirectoryList implements ComputeResult {

  @Value.Parameter
  public abstract ImmutableSortedSet<Path> getFiles();

  @Value.Parameter
  public abstract ImmutableSortedSet<Path> getDirectories();

  @Value.Parameter
  public abstract ImmutableSortedSet<Path> getSymlinks();
}
