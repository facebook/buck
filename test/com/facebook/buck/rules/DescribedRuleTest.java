/*
 * Copyright 2013-present Facebook, Inc.
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

import static com.facebook.buck.testutil.MoreAsserts.assertSetEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.shell.EchoStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

public class DescribedRuleTest {

  @Test
  public void checkConstructor() {
    BuildRuleParams params = new BuildRuleParams(
        BuildTargetFactory.newInstance("//one:example"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of(),
        IdentityPathAbsolutifier.getIdentityAbsolutifier(),
        new FakeRuleKeyBuilderFactory()
    );

    ExampleBuildable expected = new ExampleBuildable("nada");
    DescribedRule rule = new DescribedRule(new BuildRuleType("example"),
        expected,
        params);

    Buildable seen = rule.getBuildable();

    assertSame(expected, seen);
  }

  @Test
  public void canConstructRuleUsingBuilder() throws NoSuchBuildTargetException, IOException {
    class Dto implements ConstructorArg {
      public String name;
    }

    Description<Dto> description = new Description<Dto>() {
      @Override
      public BuildRuleType getBuildRuleType() {
        return new BuildRuleType("example");
      }

      @Override
      public Dto createUnpopulatedConstructorArg() {
        return new Dto();
      }

      @Override
      public Buildable createBuildable(BuildRuleParams params, Dto args) {
        return new ExampleBuildable(args.name);
      }
    };


    ProjectFilesystem filesystem = createForgivingProjectFilesystem();
    BuildRuleFactoryParams factoryParams = new BuildRuleFactoryParams(
        // "name" maps to the DTO, which is returned in the EchoStep
        ImmutableMap.of("name", "cheese"),
        filesystem,
        new BuildFileTree(ImmutableSet.<String>of()),
        new BuildTargetParser(filesystem),
        BuildTargetFactory.newInstance("//one/two:example"),
        new FakeRuleKeyBuilderFactory(),
        /* ignore file existence checks */ true
    );

    BuildContext fakeBuildContext = EasyMock.createNiceMock(BuildContext.class);
    ExecutionContext fakeExecutionContext = EasyMock.createNiceMock(ExecutionContext.class);
    BuckEventBus bus = BuckEventBusFactory.newInstance();
    final AtomicBoolean ok = new AtomicBoolean(false);
    bus.register(new Object() {
      @Subscribe
      public void echo(LogEvent event) {
        ok.set("cheese".equals(event.getMessage()));
      }
    });
    EasyMock.expect(fakeExecutionContext.getBuckEventBus()).andStubReturn(bus);

    EasyMock.replay(fakeBuildContext, fakeExecutionContext);

    DescribedRuleFactory<Dto> factory = new DescribedRuleFactory<>(description);
    DescribedRuleBuilder<Dto> builder = factory.newInstance(factoryParams);
    DescribedRule rule = builder.build(new BuildRuleResolver());
    List<Step> steps =
        rule.getBuildable().getBuildSteps(fakeBuildContext, new FakeBuildableContext());

    assertEquals(1, steps.size());
    EchoStep step = (EchoStep) Iterables.getOnlyElement(steps);

    step.execute(fakeExecutionContext);

    assertTrue(ok.get());
    // No need to verify the mocks as they're not being used as mocks
  }

  @Test
  public void addingASourcePathShouldAmendTheDepsOfARule() throws NoSuchBuildTargetException {
    // The allowable variations. We don't populate Collection<Optional<SourcePath>>.
    @SuppressWarnings("unused")
    class Dto implements ConstructorArg {
      public SourcePath path;
      public Optional<SourcePath> other;
      public ImmutableSet<SourcePath> paths;
      public Optional<List<SourcePath>> optionalPaths;
    }

    Description<Dto> description = new Description<Dto>() {
      @Override
      public BuildRuleType getBuildRuleType() {
        return new BuildRuleType("example");
      }

      @Override
      public Dto createUnpopulatedConstructorArg() {
        return new Dto();
      }

      @Override
      public Buildable createBuildable(BuildRuleParams params, Dto args) {
        return new ExampleBuildable("hello world");
      }
    };

    BuildRuleType type = new BuildRuleType("fake");
    BuildRule depRule1 = new FakeBuildRule(type, BuildTargetFactory.newInstance("//example:dep1"));
    BuildRule depRule2 = new FakeBuildRule(type, BuildTargetFactory.newInstance("//example:dep2"));
    BuildRule depRule3 = new FakeBuildRule(type, BuildTargetFactory.newInstance("//example:dep3"));
    BuildRule depRule4 = new FakeBuildRule(type, BuildTargetFactory.newInstance("//example:dep4"));

    BuildRuleResolver ruleResolver = new BuildRuleResolver(
        ImmutableMap.of(
            depRule1.getBuildTarget(), depRule1,
            depRule2.getBuildTarget(), depRule2,
            depRule3.getBuildTarget(), depRule3,
            depRule4.getBuildTarget(), depRule4));

    ProjectFilesystem filesystem = createForgivingProjectFilesystem();
    BuildRuleFactoryParams factoryParams = new BuildRuleFactoryParams(
        ImmutableMap.of(
            "path", "//example:dep1",
            "other", "//example:dep2",
            "paths", ImmutableList.of("//example:dep3"),
            "optionalPaths", ImmutableList.of("//example:dep4")),
        filesystem,
        new BuildFileTree(ImmutableSet.<String>of()),
        new BuildTargetParser(filesystem),
        BuildTargetFactory.newInstance("//one/two:example"),
        new FakeRuleKeyBuilderFactory(),
        /* ignore file existence checks */ true
    );

    DescribedRuleFactory<Dto> factory = new DescribedRuleFactory<>(description);
    DescribedRuleBuilder<Dto> builder = factory.newInstance(factoryParams);
    DescribedRule rule = builder.build(ruleResolver);

    ImmutableSortedSet<BuildRule> deps = rule.getDeps();
    assertSetEquals(
        "Should have added all resolved SourcePaths as dependencies",
        ImmutableSet.of(depRule1, depRule2, depRule3, depRule4), deps);
  }

  @Test
  public void ensureThatIfOnlyACollectionOfSourcePathsAreDeclaredTheyGetAddedAsDeps()
      throws NoSuchBuildTargetException {
    class Dto implements ConstructorArg {
      @SuppressWarnings("unused")
      public Set<SourcePath> paths;
    }

    Description<Dto> description = new Description<Dto>() {
      @Override
      public BuildRuleType getBuildRuleType() {
        return new BuildRuleType("example");
      }

      @Override
      public Dto createUnpopulatedConstructorArg() {
        return new Dto();
      }

      @Override
      public Buildable createBuildable(BuildRuleParams params, Dto args) {
        return new ExampleBuildable("hello world");
      }
    };

    BuildRuleType type = new BuildRuleType("fake");
    BuildRule depRule1 = new FakeBuildRule(type, BuildTargetFactory.newInstance("//example:dep1"));

    BuildRuleResolver ruleResolver = new BuildRuleResolver(
        ImmutableMap.of(depRule1.getBuildTarget(), depRule1));

    ProjectFilesystem filesystem = createForgivingProjectFilesystem();
    BuildRuleFactoryParams factoryParams = new BuildRuleFactoryParams(
        ImmutableMap.of("paths", ImmutableList.of("//example:dep1")),
        filesystem,
        new BuildFileTree(ImmutableSet.<String>of()),
        new BuildTargetParser(filesystem),
        BuildTargetFactory.newInstance("//one/two:example"),
        new FakeRuleKeyBuilderFactory(),
        /* ignore file existence checks */ true
    );

    DescribedRuleFactory<Dto> factory = new DescribedRuleFactory<>(description);
    DescribedRuleBuilder<Dto> builder = factory.newInstance(factoryParams);
    DescribedRule rule = builder.build(ruleResolver);

    ImmutableSortedSet<BuildRule> deps = rule.getDeps();
    assertSetEquals(ImmutableSet.of(depRule1), deps);
  }

  private ProjectFilesystem createForgivingProjectFilesystem() {
    return new ProjectFilesystem(new File(".")) {
      @Override
      public boolean exists(String pathRelativeToProjectRoot) {
        return true;
      }
    };
  }

  private static class ExampleBuildable extends AbstractBuildable {

    private final String message;

    public ExampleBuildable(String message) {
      this.message = message;
    }

    @Override
    public Collection<Path> getInputsToCompareToOutput() {
      return ImmutableSet.of();
    }

    @Override
    public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
      return builder;
    }

    @Override
    public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
        throws IOException {
      return ImmutableList.<Step>of(new EchoStep(message));
    }

    @Nullable
    @Override
    public Path getPathToOutputFile() {
      return null;
    }
  }
}
