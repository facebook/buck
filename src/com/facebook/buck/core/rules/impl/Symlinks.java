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
package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.step.fs.SymlinkPaths;
import java.util.function.Consumer;

/** Interface representing symlinks processed by {@link SymlinkTree}. */
public interface Symlinks extends AddsToRuleKey {

  /**
   * Run {@code consumer} on all {@link SourcePath}s in this groups of links. Used by {@link
   * SymlinkTree} to compose runtime deps.
   */
  void forEachSymlinkInput(Consumer<SourcePath> consumer);

  /**
   * Run {@code consumer} on all {@link BuildRule} build dependencies for this group of symlinks.
   */
  void forEachSymlinkBuildDep(SourcePathRuleFinder finder, Consumer<BuildRule> consumer);

  /**
   * Resolve all {@link SourcePath}s into {@link java.nio.file.Path}s, represented by {@link
   * SymlinkPaths} (for use with {@link com.facebook.buck.step.fs.SymlinkTreeMergeStep}.
   */
  SymlinkPaths resolveSymlinkPaths(SourcePathResolverAdapter resolver);
}
