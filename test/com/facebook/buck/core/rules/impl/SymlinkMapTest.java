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

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.rules.keys.RuleKeyBuilder;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Test;

public class SymlinkMapTest {

  /**
   * Test that adding to rule key doesn't attempt to hash the `Path`ified keys in the copmponent map
   */
  @Test
  public void ruleKey() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path dst = filesystem.getPath("dst");
    Path src = filesystem.getPath("src");
    TestDefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(
            new FakeFileHashCache(ImmutableMap.of(filesystem.resolve(src), HashCode.fromInt(123))),
            new TestActionGraphBuilder());
    RuleKeyBuilder builder = factory.newBuilderForTesting(new FakeBuildRule("//:fake"));
    AlterRuleKeys.amendKey(
        builder, new SymlinkMap(ImmutableSortedMap.of(dst, FakeSourcePath.of(src))));
  }

  @Test
  public void forEachSymlinkInput() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path dst = filesystem.getPath("dst");
    PathSourcePath src = FakeSourcePath.of(filesystem.getPath("src"));
    List<SourcePath> inputs = new ArrayList<>();
    new SymlinkMap(ImmutableSortedMap.of(dst, src)).forEachSymlinkInput(inputs::add);
    assertThat(inputs, Matchers.contains(src));
  }

  @Test
  public void forEachSymlinkBuildDep() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path dst = filesystem.getPath("dst");
    BuildRule srcRule = new FakeBuildRule("//:fake");
    SourcePath src = DefaultBuildTargetSourcePath.of(srcRule.getBuildTarget());
    List<BuildRule> deps = new ArrayList<>();
    new SymlinkMap(ImmutableSortedMap.of(dst, src))
        .forEachSymlinkBuildDep(new TestActionGraphBuilder(), deps::add);
    assertThat(deps, Matchers.empty());
  }
}
