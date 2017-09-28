/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.EventDispatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.InputDataRetriever;
import com.facebook.buck.rules.modern.InputPath;
import com.facebook.buck.rules.modern.InputPathResolver;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import org.immutables.value.Value;
import org.junit.Test;
import java.nio.file.Paths;
import java.util.Optional;

public class TargetNodeFactoryTest {

  @Test
  public void shouldDeriveExtraDepsFromInputPaths()
      throws InterruptedException, NoSuchBuildTargetException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    TargetNodeFactory factory = new TargetNodeFactory(new DefaultTypeCoercerFactory());
    BuildTarget targetDep = BuildTargetFactory.newInstance("//foo:bar");

    TargetNode<ExampleArg, ExampleDescription> node =
        factory.create(
            Hashing.sha1().hashString("cake", UTF_8),
            new ExampleDescription(),
            ExampleArg.builder()
                .setName("fish")
                .setSrc(
                    Optional.of(
                        new InputPath(
                            new ExplicitBuildTargetSourcePath(targetDep, Paths.get("foo")))))
                .build(),
            filesystem,
            BuildTargetFactory.newInstance("//example:example"),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            new DefaultCellPathResolver(filesystem.getRootPath(), ImmutableMap.of()));

    assertEquals(ImmutableSet.of(targetDep), node.getExtraDeps());
  }

  private static class ExampleDescription implements Description<ExampleArg> {

    @Override
    public Class<ExampleArg> getConstructorArgType() {
      return ExampleArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        TargetGraph targetGraph,
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        BuildRuleResolver resolver,
        CellPathResolver cellRoots,
        ExampleArg args) {
      return null;
    }
  }

  private static class Example extends ModernBuildRule<Example> implements Buildable {

    protected Example(
        BuildTarget buildTarget, ProjectFilesystem filesystem, SourcePathRuleFinder finder) {
      super(buildTarget, filesystem, finder, Example.class);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        EventDispatcher eventDispatcher,
        ProjectFilesystem filesystem,
        InputPathResolver inputPathResolver,
        InputDataRetriever inputDataRetriever,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of();
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractExampleArg extends CommonDescriptionArg {
    @Value.Default
    default Optional<InputPath> getSrc() {
      return Optional.empty();
    }
  }
}
