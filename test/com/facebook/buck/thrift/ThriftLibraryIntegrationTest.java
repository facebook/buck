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

package com.facebook.buck.thrift;

import static org.junit.Assert.assertThat;

import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.ParserNg;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ThriftLibraryIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void shouldBeAbleToConstructACxxLibraryFromThrift() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cxx", tmp);
    workspace.setUp();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ParserNg parser = new ParserNg(
        typeCoercerFactory,
        new ConstructorArgMarshaller(typeCoercerFactory));

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());
    Path compiler = new ExecutableFinder().getExecutable(
        Paths.get("echo"),
        ImmutableMap.copyOf(System.getenv()));
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setSections(
            "[thrift]",
            "compiler = " + compiler,
            "compiler2 = " + compiler,
            "cpp_library = //thrift:fake",
            "cpp_reflection_library = //thrift:fake")
        .build();

    Cell cell = Cell.createCell(
        filesystem,
        new TestConsole(),
        Watchman.NULL_WATCHMAN,
        config,
        new KnownBuildRuleTypesFactory(
            new ProcessExecutor(new TestConsole()),
            new FakeAndroidDirectoryResolver(),
            Optional.<Path>absent()),
        new FakeAndroidDirectoryResolver(),
        new DefaultClock());
    BuildTarget target = BuildTargetFactory.newInstance(filesystem, "//thrift:exe");
    TargetGraph targetGraph = parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        ImmutableSet.of(target));

    TargetNodeToBuildRuleTransformer transformer = new BuildTargetNodeToBuildRuleTransformer();

    // There was a case where the cxx library being generated wouldn't put the header into the tree
    // with the right flavour. This catches this case without us needing to stick a working thrift
    // compiler into buck's own source.
    Pair<ActionGraph, BuildRuleResolver> actionGraphAndResolver =
        new TargetGraphToActionGraph(eventBus, transformer)
        .apply(targetGraph);

    // This is to cover the case where we weren't passing flavors around correctly, which ended
    // making the binary depend 'placeholder' BuildRules instead of real ones. This is the
    // regression test for that case.
    BuildRuleResolver ruleResolver = actionGraphAndResolver.getSecond();
    BuildTarget binaryFlavor = target.withFlavors(ImmutableFlavor.of("binary"));
    ImmutableSortedSet<BuildRule> deps = ruleResolver.getRule(binaryFlavor).getDeps();
    assertThat(
        FluentIterable.from(deps)
            .anyMatch(
                new Predicate<BuildRule>() {
                  @Override
                  public boolean apply(BuildRule input) {
                    return input instanceof NoopBuildRule;
                  }
                }),
        Matchers.is(false));
  }

}
