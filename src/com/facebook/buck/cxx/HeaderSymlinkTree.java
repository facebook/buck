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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class HeaderSymlinkTree extends SymlinkTree {

  public HeaderSymlinkTree(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      ImmutableMap<Path, SourcePath> links) throws SymlinkTree.InvalidSymlinkTreeException {
    super(params, resolver, root, links);
  }

  /**
   * Get path to use as an include path to get access to the files in the tree.
   *
   * If {@link #getHeaderMap()} is present, then the path it returns needs to be passed as an
   * include path as well and it has to be passed before the path returned from this method.
   */
  public Path getIncludePath() {
    return getRoot();
  }

  /**
   * Get path to use as a system include path to get access to the files in the tree.
   *
   * As opposed to {@link #getIncludePath()}, this method does not require any other include paths
   * to be used.
   */
  public Path getSystemIncludePath() {
    return getRoot();
  }

  /**
   * Get path of the header map indexing this tree if one exists.
   */
  public Optional<Path> getHeaderMap() {
    return Optional.absent();
  }

}
