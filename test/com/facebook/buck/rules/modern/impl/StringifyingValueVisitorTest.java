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

import static org.junit.Assert.*;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
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

public class StringifyingValueVisitorTest {
  private static final ProjectFilesystem rootFilesystem =
      new FakeProjectFilesystem(Paths.get("/project/root"));

  private static class WithOutputPath implements FakeBuildable {
    @AddToRuleKey private final OutputPath output = new OutputPath("some/path");
  }

  @Test
  public void outputPath() {
    assertEquals("output:OutputPath(some/path)\n", stringify(new WithOutputPath()));
  }

  private static class WithSourcePath implements FakeBuildable {
    @AddToRuleKey private final SourcePath path = FakeSourcePath.of(rootFilesystem, "some/path");
  }

  @Test
  public void sourcePath() {
    assertEquals("path:SourcePath(/project/root/some/path)\n", stringify(new WithSourcePath()));
  }

  private static class WithSet implements FakeBuildable {
    @AddToRuleKey
    private final ImmutableSortedSet<String> present = ImmutableSortedSet.of("hello", "world", "!");

    @AddToRuleKey private final ImmutableSortedSet<Integer> empty = ImmutableSortedSet.of();
  }

  @Test
  public void set() {
    assertEquals("present:value([!, hello, world])\nempty:value([])\n", stringify(new WithSet()));
  }

  private static class WithList implements FakeBuildable {
    @AddToRuleKey
    private final ImmutableList<String> present = ImmutableList.of("hello", "world", "!");

    @AddToRuleKey private final ImmutableList<Integer> empty = ImmutableList.of();
  }

  @Test
  public void list() {
    assertEquals("present:value([hello, world, !])\nempty:value([])\n", stringify(new WithList()));
  }

  private static class WithOptional implements FakeBuildable {
    @AddToRuleKey private final Optional<String> present = Optional.of("hello");
    @AddToRuleKey private final Optional<Integer> empty = Optional.empty();
  }

  @Test
  public void optional() {
    assertEquals(
        "present:value(Optional[hello])\nempty:value(Optional.empty)\n",
        stringify(new WithOptional()));
  }

  static class Simple implements FakeBuildable {
    @AddToRuleKey private final int value = 1;
  }

  @Test
  public void simple() {
    assertEquals("value:value(1)\n", stringify(new Simple()));
  }

  static class Derived extends Simple {
    @AddToRuleKey private final double number = 2.3;
  }

  @Test
  public void superClass() {
    assertEquals("value:value(1)\nnumber:value(2.3)\n", stringify(new Derived()));
  }

  static class Empty implements FakeBuildable {}

  @Test
  public void empty() {
    assertEquals("", stringify(new Empty()));
  }

  private static class Complex implements FakeBuildable {
    @AddToRuleKey
    private final Optional<ImmutableList<ImmutableSortedSet<SourcePath>>> value =
        Optional.of(
            ImmutableList.of(
                ImmutableSortedSet.of(),
                ImmutableSortedSet.of(FakeSourcePath.of(rootFilesystem, "some/path"))));

    @AddToRuleKey private final String string = "hello";
    @AddToRuleKey private final int number = 0;
  }

  @Test
  public void complex() {
    assertEquals(
        "value:Optional<\n"
            + "  List<\n"
            + "    Set<\n"
            + "    >\n"
            + "    Set<\n"
            + "      SourcePath(/project/root/some/path)\n"
            + "    >\n"
            + "  >\n"
            + ">\n"
            + "string:value(hello)\n"
            + "number:value(0)\n",
        stringify(new Complex()));
  }

  private String stringify(Buildable value) {
    StringifyingValueVisitor visitor = new StringifyingValueVisitor();
    DefaultClassInfoFactory.forBuildable(value).visit(value, visitor);
    return visitor.getValue();
  }

  interface FakeBuildable extends Buildable {
    @Override
    default ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of();
    }
  }
}
