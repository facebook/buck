/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ValueTypeInfosTest {
  private InputRuleResolver inputRuleResolver = createStrictMock(InputRuleResolver.class);

  @SuppressWarnings("unchecked")
  private Consumer<BuildRule> buildRuleConsumer = createStrictMock(Consumer.class);

  @SuppressWarnings("unchecked")
  private BiConsumer<String, OutputPath> outputConsumer = createStrictMock(BiConsumer.class);

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void testInteger() {
    ValueTypeInfo<Integer> typeInfo =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Integer>() {});

    int value = 1;
    replay(inputRuleResolver, buildRuleConsumer, outputConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer);
  }

  @Test
  public void testString() {
    ValueTypeInfo<String> typeInfo = ValueTypeInfoFactory.forTypeToken(new TypeToken<String>() {});

    String value = "hello";
    replay(inputRuleResolver, buildRuleConsumer, outputConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer);
  }

  @Test(expected = RuntimeException.class)
  public void testPath() {
    ValueTypeInfoFactory.forTypeToken(new TypeToken<Path>() {});
  }

  @Test(expected = RuntimeException.class)
  public void testObject() {
    ValueTypeInfoFactory.forTypeToken(new TypeToken<Object>() {});
  }

  @Test
  public void testSet() {
    try {
      ValueTypeInfoFactory.forTypeToken(new TypeToken<Set<String>>() {});
      fail();
    } catch (Exception e) {
      assertThat(
          Throwables.getCausalChain(e)
              .stream()
              .map(Throwable::toString)
              .collect(Collectors.toList()),
          Matchers.hasItem(Matchers.stringContainsInOrder("Use ImmutableSortedSet")));
    }
  }

  @Test(expected = RuntimeException.class)
  public void testSortedSet() {
    ValueTypeInfoFactory.forTypeToken(new TypeToken<SortedSet<String>>() {});
  }

  @Test(expected = RuntimeException.class)
  public void testImmutableSet() {
    ValueTypeInfoFactory.forTypeToken(new TypeToken<ImmutableSet<String>>() {});
  }

  @Test(expected = RuntimeException.class)
  public void testRandomType() {
    ValueTypeInfoFactory.forTypeToken(new TypeToken<ValueTypeInfos>() {});
  }

  @Test(expected = RuntimeException.class)
  public void testBadNestedTypeParamater() {
    ValueTypeInfoFactory.forTypeToken(new TypeToken<Optional<Optional<Path>>>() {});
  }

  @Test
  public void testPathSourcePath() {
    ValueTypeInfo<SourcePath> typeInfo =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<SourcePath>() {});

    PathSourcePath sourcePath = FakeSourcePath.of(filesystem, "path");
    EasyMock.expect(inputRuleResolver.resolve(sourcePath)).andReturn(Optional.empty());

    replay(inputRuleResolver, buildRuleConsumer, outputConsumer);
    typeInfo.extractDep(sourcePath, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", sourcePath, outputConsumer);
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer);
  }

  @Test
  public void testBuildTargetSourcePath() {
    ValueTypeInfo<SourcePath> typeInfo =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<SourcePath>() {});

    BuildTarget target = BuildTarget.of(Paths.get("some"), "//some", "name");
    BuildRule rule = new FakeBuildRule(target, ImmutableSortedSet.of());
    BuildTargetSourcePath sourcePath = ExplicitBuildTargetSourcePath.of(target, Paths.get("path"));

    EasyMock.expect(inputRuleResolver.resolve(sourcePath)).andReturn(Optional.of(rule));
    buildRuleConsumer.accept(rule);

    replay(inputRuleResolver, buildRuleConsumer, outputConsumer);
    typeInfo.extractDep(sourcePath, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", sourcePath, outputConsumer);
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer);
  }

  @Test
  public void testOptional() {
    ValueTypeInfo<Optional<SourcePath>> typeInfo =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Optional<SourcePath>>() {});

    BuildTarget target = BuildTarget.of(Paths.get("some"), "//some", "name");
    BuildRule rule = new FakeBuildRule(target, ImmutableSortedSet.of());
    BuildTargetSourcePath sourcePath = ExplicitBuildTargetSourcePath.of(target, Paths.get("path"));

    Optional<SourcePath> value = Optional.of(sourcePath);
    EasyMock.expect(inputRuleResolver.resolve(sourcePath)).andReturn(Optional.of(rule));
    buildRuleConsumer.accept(rule);

    replay(inputRuleResolver, buildRuleConsumer, outputConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer);
  }

  @Test
  public void testEmptyOptional() {
    ValueTypeInfo<Optional<SourcePath>> typeInfo =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Optional<SourcePath>>() {});

    Optional<SourcePath> value = Optional.empty();
    replay(inputRuleResolver, buildRuleConsumer, outputConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer);
  }

  @Test
  public void testImmutableList() {
    ValueTypeInfo<ImmutableList<SourcePath>> typeInfo =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<ImmutableList<SourcePath>>() {});

    BuildTarget target = BuildTarget.of(Paths.get("some"), "//some", "name");
    BuildRule rule = new FakeBuildRule(target, ImmutableSortedSet.of());
    BuildTargetSourcePath targetSourcePath =
        ExplicitBuildTargetSourcePath.of(target, Paths.get("path"));

    PathSourcePath pathSourcePath = FakeSourcePath.of(filesystem, "path");
    ImmutableList<SourcePath> value = ImmutableList.of(targetSourcePath, pathSourcePath);

    EasyMock.expect(inputRuleResolver.resolve(targetSourcePath)).andReturn(Optional.of(rule));
    EasyMock.expect(inputRuleResolver.resolve(pathSourcePath)).andReturn(Optional.empty());
    buildRuleConsumer.accept(rule);

    replay(inputRuleResolver, buildRuleConsumer, outputConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer);
  }
}
