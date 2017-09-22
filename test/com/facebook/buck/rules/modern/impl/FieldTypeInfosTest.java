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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.InputPath;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.facebook.buck.rules.modern.OutputData;
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

public class FieldTypeInfosTest {
  private InputRuleResolver inputRuleResolver = createStrictMock(InputRuleResolver.class);

  @SuppressWarnings("unchecked")
  private Consumer<BuildRule> buildRuleConsumer = createStrictMock(Consumer.class);

  @SuppressWarnings("unchecked")
  private BiConsumer<String, OutputPath> outputConsumer = createStrictMock(BiConsumer.class);

  @SuppressWarnings("unchecked")
  private BiConsumer<String, OutputData> outputDataConsumer = createStrictMock(BiConsumer.class);

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void testInteger() {
    FieldTypeInfo<Integer> typeInfo =
        FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<Integer>() {});

    int value = 1;
    replay(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    typeInfo.extractOutputData("", value, outputDataConsumer);
    assertEquals(value, typeInfo.extractRuleKeyObject(value));
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
  }

  @Test
  public void testString() {
    FieldTypeInfo<String> typeInfo =
        FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<String>() {});

    String value = "hello";
    replay(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    typeInfo.extractOutputData("", value, outputDataConsumer);
    assertEquals(value, typeInfo.extractRuleKeyObject(value));
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
  }

  @Test(expected = RuntimeException.class)
  public void testPath() {
    FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<Path>() {});
  }

  @Test
  public void testSourcePath() {
    try {
      FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<SourcePath>() {});
      fail();
    } catch (Exception e) {
      assertThat(
          Throwables.getCausalChain(e)
              .stream()
              .map(Throwable::toString)
              .collect(Collectors.toList()),
          Matchers.hasItem(Matchers.stringContainsInOrder("Use InputPath or OutputPath")));
    }
  }

  @Test
  public void testBuildTargetSourcePath() {
    try {
      FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<BuildTargetSourcePath>() {});
      fail();
    } catch (Exception e) {
      assertThat(
          Throwables.getCausalChain(e)
              .stream()
              .map(Throwable::toString)
              .collect(Collectors.toList()),
          Matchers.hasItem(Matchers.stringContainsInOrder("Use InputPath or OutputPath")));
    }
  }

  @Test(expected = RuntimeException.class)
  public void testObject() {
    FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<Object>() {});
  }

  @Test
  public void testSet() {
    try {
      FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<Set<String>>() {});
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
    FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<SortedSet<String>>() {});
  }

  @Test(expected = RuntimeException.class)
  public void testImmutableSet() {
    FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<ImmutableSet<String>>() {});
  }

  @Test(expected = RuntimeException.class)
  public void testRandomType() {
    FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<FieldTypeInfos>() {});
  }

  @Test(expected = RuntimeException.class)
  public void testBadNestedTypeParamater() {
    FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<Optional<Optional<Path>>>() {});
  }

  @Test
  public void testPathInputPath() {
    FieldTypeInfo<InputPath> typeInfo =
        FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<InputPath>() {});

    PathSourcePath sourcePath = new PathSourcePath(filesystem, Paths.get("path"));
    InputPath value = new InputPath(sourcePath);
    EasyMock.expect(inputRuleResolver.resolve(value)).andReturn(Optional.empty());

    replay(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    typeInfo.extractOutputData("", value, outputDataConsumer);
    assertEquals(sourcePath, typeInfo.extractRuleKeyObject(value));
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
  }

  @Test
  public void testBuildTargetInputPath() {
    FieldTypeInfo<InputPath> typeInfo =
        FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<InputPath>() {});

    BuildTarget target = BuildTarget.of(Paths.get("some"), "//some", "name");
    BuildRule rule = new FakeBuildRule(target, ImmutableSortedSet.of());
    BuildTargetSourcePath sourcePath = new ExplicitBuildTargetSourcePath(target, Paths.get("path"));

    InputPath value = new InputPath(sourcePath);
    EasyMock.expect(inputRuleResolver.resolve(value)).andReturn(Optional.of(rule));
    buildRuleConsumer.accept(rule);

    replay(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    typeInfo.extractOutputData("", value, outputDataConsumer);
    assertEquals(sourcePath, typeInfo.extractRuleKeyObject(value));
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
  }

  @Test
  public void testOptional() {
    FieldTypeInfo<Optional<InputPath>> typeInfo =
        FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<Optional<InputPath>>() {});

    BuildTarget target = BuildTarget.of(Paths.get("some"), "//some", "name");
    BuildRule rule = new FakeBuildRule(target, ImmutableSortedSet.of());
    BuildTargetSourcePath sourcePath = new ExplicitBuildTargetSourcePath(target, Paths.get("path"));

    InputPath inputPath = new InputPath(sourcePath);
    Optional<InputPath> value = Optional.of(inputPath);
    EasyMock.expect(inputRuleResolver.resolve(inputPath)).andReturn(Optional.of(rule));
    buildRuleConsumer.accept(rule);

    replay(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    typeInfo.extractOutputData("", value, outputDataConsumer);
    assertEquals(Optional.of(sourcePath), typeInfo.extractRuleKeyObject(value));
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
  }

  @Test
  public void testEmptyOptional() {
    FieldTypeInfo<Optional<InputPath>> typeInfo =
        FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<Optional<InputPath>>() {});

    Optional<InputPath> value = Optional.empty();
    replay(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    typeInfo.extractOutputData("", value, outputDataConsumer);
    assertEquals(Optional.empty(), typeInfo.extractRuleKeyObject(value));
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
  }

  @Test
  public void testImmutableList() {
    FieldTypeInfo<ImmutableList<InputPath>> typeInfo =
        FieldTypeInfoFactory.forFieldTypeToken(new TypeToken<ImmutableList<InputPath>>() {});

    BuildTarget target = BuildTarget.of(Paths.get("some"), "//some", "name");
    BuildRule rule = new FakeBuildRule(target, ImmutableSortedSet.of());
    BuildTargetSourcePath targetSourcePath =
        new ExplicitBuildTargetSourcePath(target, Paths.get("path"));
    InputPath targetInputPath = new InputPath(targetSourcePath);

    PathSourcePath pathSourcePath = new PathSourcePath(filesystem, Paths.get("path"));
    InputPath pathInputPath = new InputPath(pathSourcePath);
    ImmutableList<InputPath> value = ImmutableList.of(targetInputPath, pathInputPath);

    EasyMock.expect(inputRuleResolver.resolve(targetInputPath)).andReturn(Optional.of(rule));
    EasyMock.expect(inputRuleResolver.resolve(pathInputPath)).andReturn(Optional.empty());
    buildRuleConsumer.accept(rule);

    replay(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
    typeInfo.extractDep(value, inputRuleResolver, buildRuleConsumer);
    typeInfo.extractOutput("", value, outputConsumer);
    typeInfo.extractOutputData("", value, outputDataConsumer);
    assertEquals(
        ImmutableList.of(targetSourcePath, pathSourcePath), typeInfo.extractRuleKeyObject(value));
    verify(inputRuleResolver, buildRuleConsumer, outputConsumer, outputDataConsumer);
  }
}
