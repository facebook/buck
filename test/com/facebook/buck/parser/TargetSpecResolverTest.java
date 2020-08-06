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

package com.facebook.buck.parser;

import static com.google.common.base.Charsets.UTF_8;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.parser.TargetSpecResolver.FlavorEnhancer;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.BuildFileSpec;
import com.facebook.buck.parser.spec.TargetNodePredicateSpec;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class TargetSpecResolverTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Rule
  public CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> executor =
      CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));

  private ProjectWorkspace workspace;
  private ProjectFilesystem filesystem;
  private Path cellRoot;
  private Cells cell;
  private BuckEventBus eventBus;
  private PerBuildStateFactory perBuildStateFactory;
  private TypeCoercerFactory typeCoercerFactory;
  private Parser parser;
  private ParserPythonInterpreterProvider parserPythonInterpreterProvider;
  private ConstructorArgMarshaller constructorArgMarshaller;
  private ListeningExecutorService executorService;
  private TargetSpecResolver targetNodeTargetSpecResolver;
  private FlavorEnhancer flavorEnhancer;

  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "target_specs", tmp);
    workspace.setUp();

    cellRoot = tmp.getRoot();
    filesystem = TestProjectFilesystems.createProjectFilesystem(cellRoot);
    cell = new TestCellBuilder().setFilesystem(filesystem).build();
    eventBus = BuckEventBusForTests.newInstance();
    typeCoercerFactory = new DefaultTypeCoercerFactory();
    constructorArgMarshaller = new DefaultConstructorArgMarshaller();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);
    ParserConfig parserConfig = cell.getRootCell().getBuckConfig().getView(ParserConfig.class);
    ExecutableFinder executableFinder = new ExecutableFinder();
    parserPythonInterpreterProvider =
        new ParserPythonInterpreterProvider(parserConfig, executableFinder);
    perBuildStateFactory =
        new PerBuildStateFactory(
            typeCoercerFactory,
            constructorArgMarshaller,
            knownRuleTypesProvider,
            parserPythonInterpreterProvider,
            WatchmanFactory.NULL_WATCHMAN,
            eventBus,
            new ParsingUnconfiguredBuildTargetViewFactory(),
            UnconfiguredTargetConfiguration.INSTANCE);

    targetNodeTargetSpecResolver =
        TestTargetSpecResolverFactory.create(executor.get(), cell.getCellProvider(), eventBus);
    parser = TestParserFactory.create(executor.get(), cell.getRootCell(), perBuildStateFactory);
    flavorEnhancer = (target, targetNode, targetType) -> target;
    executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));
  }

  @After
  public void tearDown() {
    executorService.shutdown();
  }

  @Test
  public void whenAllRulesRequestedWithTrueFilterThenMultipleRulesReturned()
      throws BuildFileParseException, IOException, InterruptedException {

    ImmutableList<ImmutableSet<BuildTarget>> targets =
        resolve(
            ImmutableList.of(
                TargetNodePredicateSpec.of(
                    BuildFileSpec.fromRecursivePath(
                        CellRelativePath.of(
                            cell.getRootCell().getCanonicalName(), ForwardRelativePath.of(""))))));

    ImmutableSet<BuildTarget> expectedTargets =
        ImmutableSet.of(
            BuildTargetFactory.newInstance("//src", "foo"),
            BuildTargetFactory.newInstance("//src", "bar"),
            BuildTargetFactory.newInstance("//src", "baz"));
    assertEquals("Should have returned all rules.", ImmutableList.of(expectedTargets), targets);
  }

  @Test(timeout = 20000)
  public void resolveTargetSpecsDoesNotHangOnException() throws Exception {
    Path buckFile = cellRoot.resolve("foo/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(buckFile, "# empty".getBytes(UTF_8));

    buckFile = cellRoot.resolve("bar/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(buckFile, "I do not parse as python".getBytes(UTF_8));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Buck wasn't able to parse");
    thrown.expectMessage(Paths.get("bar/BUCK").toString());

    resolve(
        ImmutableList.of(
            TargetNodePredicateSpec.of(
                BuildFileSpec.fromRecursivePath(
                    CellRelativePath.of(
                        cell.getRootCell().getCanonicalName(), ForwardRelativePath.of("bar")))),
            TargetNodePredicateSpec.of(
                BuildFileSpec.fromRecursivePath(
                    CellRelativePath.of(
                        cell.getRootCell().getCanonicalName(), ForwardRelativePath.of("foo"))))));
  }

  @Test
  public void resolveTargetSpecsPreservesOrder() throws Exception {
    BuildTarget foo = BuildTargetFactory.newInstance("//foo:foo");
    Path buckFile = cellRoot.resolve("foo/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(buckFile, "genrule(name='foo', out='foo', cmd='foo')".getBytes(UTF_8));

    BuildTarget bar = BuildTargetFactory.newInstance("//bar:bar");
    buckFile = cellRoot.resolve("bar/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(buckFile, "genrule(name='bar', out='bar', cmd='bar')".getBytes(UTF_8));

    ImmutableList<ImmutableSet<BuildTarget>> targets =
        resolve(
            ImmutableList.of(
                TargetNodePredicateSpec.of(
                    BuildFileSpec.fromRecursivePath(
                        CellRelativePath.of(
                            cell.getRootCell().getCanonicalName(), ForwardRelativePath.of("bar")))),
                TargetNodePredicateSpec.of(
                    BuildFileSpec.fromRecursivePath(
                        CellRelativePath.of(
                            cell.getRootCell().getCanonicalName(),
                            ForwardRelativePath.of("foo"))))));
    assertThat(targets, equalTo(ImmutableList.of(ImmutableSet.of(bar), ImmutableSet.of(foo))));

    targets =
        resolve(
            ImmutableList.of(
                TargetNodePredicateSpec.of(
                    BuildFileSpec.fromRecursivePath(
                        CellRelativePath.of(
                            cell.getRootCell().getCanonicalName(), ForwardRelativePath.of("foo")))),
                TargetNodePredicateSpec.of(
                    BuildFileSpec.fromRecursivePath(
                        CellRelativePath.of(
                            cell.getRootCell().getCanonicalName(),
                            ForwardRelativePath.of("bar"))))));
    assertThat(targets, equalTo(ImmutableList.of(ImmutableSet.of(foo), ImmutableSet.of(bar))));
  }

  @Test
  public void resolveTargetSpecsIgnoresBuckout() throws Exception {
    Path buckout = filesystem.getBuckPaths().getBuckOut();
    Path buckFile = cellRoot.resolve(buckout.resolve("BUCK"));
    Files.createDirectories(buckFile.getParent());
    Files.write(buckFile, "genrule(name='foo', out='foo', cmd='foo')".getBytes(UTF_8));

    ImmutableList<ImmutableSet<BuildTarget>> targets =
        resolve(
            ImmutableList.of(
                TargetNodePredicateSpec.of(
                    BuildFileSpec.fromRecursivePath(
                        CellRelativePath.of(
                            cell.getRootCell().getCanonicalName(),
                            ForwardRelativePath.ofPath(buckout))))));
    assertThat(targets, equalTo(ImmutableList.of(ImmutableSet.of())));
  }

  private ImmutableList<ImmutableSet<BuildTarget>> resolve(Iterable<? extends TargetNodeSpec> specs)
      throws InterruptedException {
    PerBuildState state =
        perBuildStateFactory.create(
            ParsingContext.builder(cell.getRootCell(), executorService).build(),
            parser.getPermState());
    return targetNodeTargetSpecResolver.resolveTargetSpecs(
        cell.getRootCell(), specs, Optional.empty(), flavorEnhancer, state, TargetNodeSpec::filter);
  }
}
