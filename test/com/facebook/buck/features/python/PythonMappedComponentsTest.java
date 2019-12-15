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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PythonMappedComponentsTest {

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
        builder, PythonMappedComponents.of(ImmutableSortedMap.of(dst, FakeSourcePath.of(src))));
  }

  @Test
  public void forEachInput() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePath input1 = PathSourcePath.of(filesystem, filesystem.getPath("src"));
    SourcePath input2 = DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:rule"));
    PythonComponents components =
        PythonMappedComponents.of(
            ImmutableSortedMap.of(
                filesystem.getPath("key1"), input1, filesystem.getPath("key2"), input2));
    List<SourcePath> inputs = new ArrayList<>();
    components.forEachInput(inputs::add);
    assertThat(inputs, Matchers.contains(input1, input2));
  }

  @Test
  public void resolvePythonComponents() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePath input1 = PathSourcePath.of(filesystem, filesystem.getPath("src1"));
    SourcePath input2 = PathSourcePath.of(filesystem, filesystem.getPath("src2"));
    PythonComponents components =
        PythonMappedComponents.of(
            ImmutableSortedMap.of(
                filesystem.getPath("key1"), input1, filesystem.getPath("key2"), input2));
    Map<Path, Path> resolved = new HashMap<>();
    components
        .resolvePythonComponents(new TestActionGraphBuilder().getSourcePathResolver())
        .forEachPythonComponent(resolved::put);
    assertThat(
        resolved,
        Matchers.equalTo(
            ImmutableMap.of(
                filesystem.getPath("key1"),
                filesystem.resolve("src1"),
                filesystem.getPath("key2"),
                filesystem.resolve("src2"))));
  }
}
