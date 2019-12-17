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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.step.fs.SymlinkDirPaths;
import com.facebook.buck.step.fs.SymlinkPaths;
import java.util.function.Consumer;

/** A {@link Symlinks} modeling a directory whose sub-paths should all be symlinked. */
public class SymlinkDir implements Symlinks {

  // TODO(agallagher): When symlinking, we only really care about the directory structure and not
  //  also the contents of all files.  Ideally, we'd support some rule key override to model this.
  @AddToRuleKey private final SourcePath directory;

  public SymlinkDir(SourcePath directory) {
    this.directory = directory;
  }

  @Override
  public void forEachSymlinkInput(Consumer<SourcePath> consumer) {
    consumer.accept(directory);
  }

  @Override
  public SymlinkPaths resolveSymlinkPaths(SourcePathResolverAdapter resolver) {
    return new SymlinkDirPaths(resolver.getAbsolutePath(directory));
  }

  @Override
  public void forEachSymlinkBuildDep(SourcePathRuleFinder finder, Consumer<BuildRule> consumer) {
    finder.filterBuildRuleInputs(directory).forEach(consumer);
  }
}
