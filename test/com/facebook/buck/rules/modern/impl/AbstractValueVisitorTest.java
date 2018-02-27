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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

abstract class AbstractValueVisitorTest {
  protected static final ProjectFilesystem rootFilesystem =
      new FakeProjectFilesystem(Paths.get("/project/root"));

  protected static final ProjectFilesystem otherFilesystem =
      new FakeProjectFilesystem(Paths.get("/project/other"));

  @Test
  public abstract void outputPath() throws Exception;

  @Test
  public abstract void sourcePath() throws Exception;

  @Test
  public abstract void set() throws Exception;

  @Test
  public abstract void list() throws Exception;

  @Test
  public abstract void optional() throws Exception;

  @Test
  public abstract void simple() throws Exception;

  @Test
  public abstract void superClass() throws Exception;

  @Test
  public abstract void empty() throws Exception;

  @Test
  public abstract void addsToRuleKey() throws Exception;

  @Test
  public abstract void complex() throws Exception;

  @Test
  public abstract void buildTarget() throws Exception;

  public interface FakeBuildable extends Buildable {
    @Override
    default ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of();
    }
  }

  public static class WithBuildTarget implements FakeBuildable {
    @AddToRuleKey
    final BuildTarget target =
        BuildTargetFactory.newInstance(
            otherFilesystem.getRootPath(), "other//some:target#flavor1,flavor2");
  }

  public static class WithOutputPath implements FakeBuildable {
    @AddToRuleKey final OutputPath output = new OutputPath("some/path");
  }

  public static class WithSourcePath implements FakeBuildable {
    @AddToRuleKey final SourcePath path = FakeSourcePath.of(rootFilesystem, "some/path");
  }

  public static class WithSet implements FakeBuildable {
    @AddToRuleKey
    private final ImmutableSortedSet<String> present = ImmutableSortedSet.of("hello", "world", "!");

    @AddToRuleKey private final ImmutableSortedSet<Integer> empty = ImmutableSortedSet.of();
  }

  public static class WithList implements FakeBuildable {
    @AddToRuleKey
    private final ImmutableList<String> present = ImmutableList.of("hello", "world", "!");

    @AddToRuleKey private final ImmutableList<Integer> empty = ImmutableList.of();
  }

  public static class WithOptional implements FakeBuildable {
    @AddToRuleKey private final Optional<String> present = Optional.of("hello");
    @AddToRuleKey private final Optional<Integer> empty = Optional.empty();
  }

  public static class Simple implements FakeBuildable {
    @AddToRuleKey private final String string = "string";
    @AddToRuleKey private final int integer = 1;
    @AddToRuleKey private final Character character = 'c';
    @AddToRuleKey private final float value = 2.50f;
    @AddToRuleKey private final ImmutableList<Double> doubles = ImmutableList.of(1.1, 2.2, 3.3);
  }

  public static class Derived extends Simple {
    @AddToRuleKey private final double number = 2.3;
  }

  public static class Empty implements FakeBuildable {}

  public static class Appendable implements AddsToRuleKey {
    @AddToRuleKey final SourcePath sp = FakeSourcePath.of(rootFilesystem, "appendable.path");
  }

  public static class NestedAppendable implements AddsToRuleKey {
    @AddToRuleKey final Optional<Appendable> appendable = Optional.of(new Appendable());
  }

  public static class WithAddsToRuleKey implements FakeBuildable {
    @AddToRuleKey final NestedAppendable nested = new NestedAppendable();

    @AddToRuleKey
    private final ImmutableList<AddsToRuleKey> list =
        ImmutableList.of(new Appendable(), new Appendable());
  }

  public static class Complex implements FakeBuildable {
    @AddToRuleKey
    final Optional<ImmutableList<ImmutableSortedSet<SourcePath>>> value =
        Optional.of(
            ImmutableList.of(
                ImmutableSortedSet.of(),
                ImmutableSortedSet.of(
                    FakeSourcePath.of(rootFilesystem, "some/path"),
                    DefaultBuildTargetSourcePath.of(
                        BuildTargetFactory.newInstance(
                            rootFilesystem.getRootPath(), "//some/build:target")))));

    @AddToRuleKey private final String string = "hello";
    @AddToRuleKey private final int number = 0;

    @AddToRuleKey
    final ImmutableList<OutputPath> outputs =
        ImmutableList.of(new OutputPath("hello.txt"), new OutputPath("world.txt"));

    @AddToRuleKey final OutputPath otherOutput = new OutputPath("other.file");

    @AddToRuleKey final AddsToRuleKey appendable = new Appendable();
  }
}
