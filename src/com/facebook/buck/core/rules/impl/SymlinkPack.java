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
import com.facebook.buck.step.fs.SymlinkPackPaths;
import com.facebook.buck.step.fs.SymlinkPaths;
import com.google.common.collect.ImmutableList;
import java.util.function.Consumer;

/**
 * Class compose multiple separate {@link Symlinks} and present them as a single {@link Symlinks}.
 *
 * <p>This abstraction allows descriptions to: 1) avoid expensive symlink map merging into a
 * monolithic {@link SymlinkMap} and 2) combine links from otherwise incompatible {@link SymlinkMap}
 * and {@link SymlinkDir} groups.
 */
// NOTE(agallagher): We could just as easily make `SymlinkTree` take a `List<Symlinks>` instead of
// of having this helper class.  However, in the future, it'd be nice to use this class as a layer
// of abstraction to provide description-specific work like 1) conflict detection (e.g. Python
// descriptions can define a specialization `SymlinkPack` to detect conflicts at the Python
// module-level rather than relying on less user-friendly low-level link conflict errors) and 2)
// adding in custom links like otherwise missing `__init__.py` files for packages (which we
// currently do in a less action-graph friendly way).
public class SymlinkPack implements Symlinks {

  @AddToRuleKey private final ImmutableList<Symlinks> links;

  public SymlinkPack(ImmutableList<Symlinks> links) {
    this.links = links;
  }

  @Override
  public void forEachSymlinkInput(Consumer<SourcePath> consumer) {
    links.forEach(l -> l.forEachSymlinkInput(consumer));
  }

  @Override
  public SymlinkPaths resolveSymlinkPaths(SourcePathResolverAdapter resolver) {
    return new SymlinkPackPaths(
        links.stream()
            .map(l -> l.resolveSymlinkPaths(resolver))
            .collect(ImmutableList.toImmutableList()));
  }

  @Override
  public void forEachSymlinkBuildDep(SourcePathRuleFinder finder, Consumer<BuildRule> consumer) {
    links.forEach(l -> l.forEachSymlinkBuildDep(finder, consumer));
  }
}
