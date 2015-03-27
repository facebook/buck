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

import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CacheMode;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class HeaderMapFile extends AbstractBuildRule implements AbiRule {

  private final Path output;
  private final ImmutableMap<Path, SourcePath> entries;

  public HeaderMapFile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path output,
      ImmutableMap<Path, SourcePath> entries) {
    super(params, resolver);
    this.output = output;
    this.entries = entries;
  }

  /**
   * Resolve {@link com.facebook.buck.rules.SourcePath} references in the link map.
   */
  protected ImmutableMap<Path, Path> resolveEntries() {
    ImmutableMap.Builder<Path, Path> resolvedEntries = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, SourcePath> entry : entries.entrySet()) {
      resolvedEntries.put(entry.getKey(), getResolver().getPath(entry.getValue()));
    }
    return resolvedEntries.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new HeaderMapFileStep(output, resolveEntries()));
  }

  // Put the link map into the rule key, as if it changes at all, we need to
  // re-run it.
  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    List<Path> keyList = Lists.newArrayList(entries.keySet());
    Collections.sort(keyList);
    for (Path key : keyList) {
      builder.setReflectively(
          "entry(" + key.toString() + ")",
          getResolver().getPath(entries.get(key)).toString());
    }
    return builder;
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  // There are no files we care about which would cause us to need to re-create the
  // header map.
  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  // We never want to cache this step, as the output contains absolute paths.
  @Override
  public CacheMode getCacheMode() {
    return CacheMode.DISABLED;
  }

  // Since we're just setting up a map to existing files, we don't actually need to
  // re-run this rule if our deps change in any way.  We only need to re-run if our map
  // changes, which is modeled above in the rule key.
  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return Sha1HashCode.fromHashCode(HashCode.fromInt(0));
  }

}
