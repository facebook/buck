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
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.config.CellConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanDiagnosticCache;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

public class ThriftLibraryIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void shouldBeAbleToConstructACxxLibraryFromThrift() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cxx", tmp);
    workspace.setUp();

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

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory(
        ObjectMappers.newDefaultInstance());
    Parser parser = new Parser(
        new BroadcastEventListener(),
        config.getView(ParserConfig.class),
        typeCoercerFactory,
        new ConstructorArgMarshaller(typeCoercerFactory));

    Cell cell = Cell.createRootCell(
        filesystem,
        Watchman.NULL_WATCHMAN,
        config,
        CellConfig.of(),
        new KnownBuildRuleTypesFactory(
            new ProcessExecutor(new TestConsole()),
            new FakeAndroidDirectoryResolver()),
        new WatchmanDiagnosticCache());
    BuildTarget target = BuildTargetFactory.newInstance(filesystem, "//thrift:exe");
    TargetGraph targetGraph = parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
        ImmutableSet.of(target));

    // There was a case where the cxx library being generated wouldn't put the header into the tree
    // with the right flavour. This catches this case without us needing to stick a working thrift
    // compiler into buck's own source.
    ActionGraphAndResolver actionGraphAndResolver = ActionGraphCache.getFreshActionGraph(
        eventBus, targetGraph);

    // This is to cover the case where we weren't passing flavors around correctly, which ended
    // making the binary depend 'placeholder' BuildRules instead of real ones. This is the
    // regression test for that case.
    BuildRuleResolver ruleResolver = actionGraphAndResolver.getResolver();
    BuildTarget binaryFlavor = target.withFlavors(ImmutableFlavor.of("binary"));
    ImmutableSortedSet<BuildRule> deps = ruleResolver.getRule(binaryFlavor).getDeps();
    assertThat(
        FluentIterable.from(deps)
            .anyMatch(
                input -> input instanceof NoopBuildRule),
        Matchers.is(false));
  }

}
