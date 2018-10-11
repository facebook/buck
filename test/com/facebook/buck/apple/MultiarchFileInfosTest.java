/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import org.junit.Test;

public class MultiarchFileInfosTest {

  static SourcePathResolver newSourcePathResolver() {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    return DefaultSourcePathResolver.from(ruleFinder);
  }

  @Test
  public void testOutputFormatStringEmptyThinRules() {
    SourcePathResolver pathResolver = newSourcePathResolver();

    ImmutableSortedSet.Builder<SourcePath> inputsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet<SourcePath> inputs = inputsBuilder.build();

    String outputFormatString =
        MultiarchFileInfos.getMultiarchOutputFormatString(pathResolver, inputs);

    assertThat(outputFormatString, equalTo("%s"));
  }

  @Test
  public void testOutputFormatStringSingleThinRule() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = newSourcePathResolver();

    ImmutableSortedSet<SourcePath> inputs =
        ImmutableSortedSet.of(PathSourcePath.of(filesystem, Paths.get("libNiceLibrary.a")));

    String outputFormatString =
        MultiarchFileInfos.getMultiarchOutputFormatString(pathResolver, inputs);

    assertThat(outputFormatString, equalTo("%s/libNiceLibrary.a"));
  }

  @Test
  public void testOutputFormatStringDifferentOutputFileNameThinRules() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = newSourcePathResolver();

    ImmutableSortedSet.Builder<SourcePath> inputsBuilder = ImmutableSortedSet.naturalOrder();

    inputsBuilder.add(PathSourcePath.of(filesystem, Paths.get("libNiceLibrary.a")));
    inputsBuilder.add(PathSourcePath.of(filesystem, Paths.get("libBadLibrary.a")));

    ImmutableSortedSet<SourcePath> inputs = inputsBuilder.build();

    String outputFormatString =
        MultiarchFileInfos.getMultiarchOutputFormatString(pathResolver, inputs);

    assertThat(outputFormatString, equalTo("%s"));
  }

  @Test
  public void testOutputFormatStringSameOutputFileNameThinRules() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = newSourcePathResolver();

    ImmutableSortedSet.Builder<SourcePath> inputsBuilder = ImmutableSortedSet.naturalOrder();

    inputsBuilder.add(PathSourcePath.of(filesystem, Paths.get("libNiceLibrary.a")));
    inputsBuilder.add(PathSourcePath.of(filesystem, Paths.get("libNiceLibrary.a")));

    ImmutableSortedSet<SourcePath> inputs = inputsBuilder.build();

    String outputFormatString =
        MultiarchFileInfos.getMultiarchOutputFormatString(pathResolver, inputs);

    assertThat(outputFormatString, equalTo("%s/libNiceLibrary.a"));
  }
}
