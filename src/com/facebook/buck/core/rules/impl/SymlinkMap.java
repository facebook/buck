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
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.step.fs.SymlinkMapsPaths;
import com.facebook.buck.step.fs.SymlinkPaths;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link Symlinks} implemented via a static {@link ImmutableMap} of destination links to {@link
 * com.facebook.buck.core.sourcepath.SourcePath}s.
 */
public class SymlinkMap implements Symlinks {

  private final ImmutableMap<Path, SourcePath> links;

  // `Path` and `SourcePath` objects cause rule key hashing to hash the contents of the files they
  // represent.  However, since symlinks only care about the paths they point to, and not the
  // content behind them, generate a "sanitized" map using `String`s for keys and
  // `NonHashableSourcePathContainer` for values to make sure the rule key avoids rebuilds when only
  // the content of these files change.
  @AddToRuleKey
  private final Supplier<ImmutableSortedMap<String, NonHashableSourcePathContainer>>
      linksForRuleKey = this::getLinksForRuleKey;

  private ImmutableSortedMap<String, NonHashableSourcePathContainer> getLinksForRuleKey() {
    ImmutableSortedMap.Builder<String, NonHashableSourcePathContainer> linksForRuleKeyBuilder =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<Path, SourcePath> entry : links.entrySet()) {
      linksForRuleKeyBuilder.put(
          entry.getKey().toString(), new NonHashableSourcePathContainer(entry.getValue()));
    }
    return linksForRuleKeyBuilder.build();
  }

  public SymlinkMap(ImmutableMap<Path, SourcePath> links) {
    this.links = links;
  }

  @Override
  public void forEachSymlinkInput(Consumer<SourcePath> consumer) {
    links.values().forEach(consumer);
  }

  @Override
  public SymlinkPaths resolveSymlinkPaths(SourcePathResolverAdapter resolver) {
    return new SymlinkMapsPaths(resolver.getMappedPaths(links));
  }

  // Despite the fact that we have `SourcePath`s in the link map, we don't actually them to be
  // generated when creating the symlinks.  So, return an empty set of build deps to model this
  // case.
  @Override
  public void forEachSymlinkBuildDep(SourcePathRuleFinder finder, Consumer<BuildRule> consumer) {}
}
