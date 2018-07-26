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

package com.facebook.buck.crosscell;

import static com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolGetter;
import static com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolsAndDtNeeded;
import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.config.KnownConfigurationRuleTypes;
import com.facebook.buck.core.rules.config.impl.PluginBasedKnownConfigurationRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownBuildRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.parser.DefaultParser;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.TargetSpecResolver;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

/** Cross-cell related integration tests that don't fit anywhere else. */
public class InterCellIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void ensureThatNormalBuildsWorkAsExpected() throws IOException {
    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "inter-cell/export-file/secondary", tmp);
    secondary.setUp();

    ProcessResult result = secondary.runBuckBuild("//:hello");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseAnExportFileXRepoTarget() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    String expected = secondary.getFileContents("hello.txt");
    Path path = primary.buildAndReturnOutput("//:exported-file");

    String actual = new String(Files.readAllBytes(path), UTF_8);

    assertEquals(expected, actual);

    Path secondaryPath = primary.buildAndReturnRelativeOutput("secondary//:hello");
    actual = new String(Files.readAllBytes(secondary.resolve(secondaryPath)), UTF_8);
    assertEquals(expected, actual);
  }

  @Test
  public void shouldBeAbleToUseTargetsCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProcessResult result = primary.runBuckCommand("targets", "--show-target-hash", "//:cxxbinary");
    result.assertSuccess();

    ProcessResult result2 = primary.runBuckCommand("targets", "secondary//:cxxlib");
    result2.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseQueryCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace primary = createWorkspace("inter-cell/multi-cell/primary");
    ProjectWorkspace secondary = createWorkspace("inter-cell/multi-cell/secondary");
    ProjectWorkspace ternary = createWorkspace("inter-cell/multi-cell/ternary");
    registerCell(secondary, "ternary", ternary);
    registerCell(primary, "secondary", secondary);
    registerCell(primary, "ternary", ternary);

    primary.runBuckCommand("targets", "--show-target-hash", "//:cxxbinary");
    secondary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib");
    ternary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib2");

    ProcessResult result = primary.runBuckCommand("query", "deps(%s)", "//:cxxbinary");
    result.assertSuccess();
    assertThat(result.getStdout(), is(primary.getFileContents("stdout-cross-cell-dep")));
  }

  @Test
  public void shouldBeAbleToUseProjectCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();

    ProcessResult result = primary.runBuckCommand("project", "//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseACxxLibraryXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();

    ProcessResult result = primary.runBuckBuild("//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseMultipleXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace primary = createWorkspace("inter-cell/multi-cell/primary");
    ProjectWorkspace secondary = createWorkspace("inter-cell/multi-cell/secondary");
    ProjectWorkspace ternary = createWorkspace("inter-cell/multi-cell/ternary");
    registerCell(secondary, "ternary", ternary);
    registerCell(primary, "secondary", secondary);
    registerCell(primary, "ternary", ternary);

    primary.runBuckCommand("targets", "--show-target-hash", "//:cxxbinary");
    secondary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib");
    ternary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib2");

    ProcessResult result = primary.runBuckBuild("//:cxxbinary");
    result.assertSuccess();
  }

  @Test
  public void xCellCxxLibraryBuildsShouldBeHermetic() throws InterruptedException, IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    primary.runBuckBuild("//:cxxbinary");
    ImmutableMap<String, HashCode> firstPrimaryObjectFiles = findObjectFiles(primary);
    ImmutableMap<String, HashCode> firstObjectFiles = findObjectFiles(secondary);

    // Now recreate an identical checkout
    cells = prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    primary = cells.getFirst();
    secondary = cells.getSecond();

    primary.runBuckBuild("//:cxxbinary");
    ImmutableMap<String, HashCode> secondPrimaryObjectFiles = findObjectFiles(primary);
    ImmutableMap<String, HashCode> secondObjectFiles = findObjectFiles(secondary);

    assertEquals(firstPrimaryObjectFiles, secondPrimaryObjectFiles);
    assertEquals(firstObjectFiles, secondObjectFiles);

    // TODO(yiding): The binaries are not identical due to changes to the relative path components.
    // Relative path from the binary's cell is embedded in order to allow tooling to correctly find
    // debug symbols.
  }

  private ImmutableMap<String, HashCode> findObjectFiles(ProjectWorkspace workspace)
      throws IOException {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    Path buckOut = workspace.getPath(filesystem.getBuckPaths().getBuckOut());

    ImmutableMap.Builder<String, HashCode> objectHashCodes = ImmutableMap.builder();
    Files.walkFileTree(
        buckOut,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (MorePaths.getFileExtension(file).equals("o")) {
              HashCode hash = MorePaths.asByteSource(file).hash(Hashing.sha1());
              objectHashCodes.put(buckOut.relativize(file).toString(), hash);
            }
            return FileVisitResult.CONTINUE;
          }
        });

    ImmutableMap<String, HashCode> toReturn = objectHashCodes.build();
    Preconditions.checkState(!toReturn.isEmpty());
    return toReturn;
  }

  @Test
  public void shouldBeAbleToUseAJavaLibraryTargetXCell() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/java/primary", "inter-cell/java/secondary");
    ProjectWorkspace primary = cells.getFirst();

    primary.runBuckBuild("//:primary_lib").assertSuccess();
    primary.runBuckBuild("//:java-binary", "-v", "5").assertSuccess();
  }

  @Test
  public void shouldBeAbleToCompileWithBootclasspathXCell() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/java/primary", "inter-cell/java/secondary");
    ProjectWorkspace primary = cells.getFirst();

    String systemBootclasspath = System.getProperty("sun.boot.class.path");
    ProcessResult result =
        primary.runBuckBuild(
            "//:java-binary",
            "--config",
            "java.source_level=7",
            "--config",
            "java.target_level=7",
            "--config",
            String.format(
                "//java.bootclasspath-7=primary.jar%s%s", File.pathSeparator, systemBootclasspath),
            "--config",
            String.format(
                "secondary//java.bootclasspath-7=secondary.jar%s%s",
                File.pathSeparator, systemBootclasspath),
            "-v",
            "5");
    result.assertSuccess();

    List<String> verboseLogs =
        Splitter.on('\n').trimResults().omitEmptyStrings().splitToList(result.getStderr());
    // Check the javac invocations for properly a resolved bootclasspath and that we aren't
    // accidentally mixing bootclasspaths
    assertThat(
        verboseLogs,
        Matchers.hasItem(
            Matchers.allOf(
                containsString("javac"),
                containsString("-bootclasspath"),
                containsString(String.format("%sprimary.jar", File.separator)),
                containsString("primary_lib"))));
    assertThat(
        verboseLogs,
        Matchers.hasItem(
            Matchers.allOf(
                containsString("javac"),
                containsString("-bootclasspath"),
                containsString(String.format("%ssecondary.jar", File.separator)),
                containsString("secondary_lib"),
                not(containsString("primary_lib")))));
  }

  @Test
  public void buildFileNamesCanBeDifferentCrossCell() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/build-file-names/primary", "inter-cell/build-file-names/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    Path output = primary.buildAndReturnOutput("//:export");
    String expected = secondary.getFileContents("hello-world.txt");

    assertEquals(expected, new String(Files.readAllBytes(output), UTF_8));
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void xCellVisibilityShouldWorkAsExpected()
      throws IOException, InterruptedException, BuildFileParseException {
    thrown.expect(HumanReadableException.class);
    parseTargetForXCellVisibility("//:not-visible-target");
  }

  @Test
  public void xCellVisibilityPatternsBasedOnPublicBuildTargetsWork()
      throws InterruptedException, BuildFileParseException, IOException {
    parseTargetForXCellVisibility("//:public-target");
  }

  @Test
  public void xCellVisibilityPatternsBasedOnExplicitBuildTargetsWork()
      throws InterruptedException, BuildFileParseException, IOException {
    parseTargetForXCellVisibility("//:visible-target");
  }

  @Test
  public void xCellSingleDirectoryVisibilityPatternsWork()
      throws InterruptedException, BuildFileParseException, IOException {
    parseTargetForXCellVisibility("//sub2:directory");
  }

  @Test
  public void xCellSubDirectoryVisibilityPatternsWork()
      throws InterruptedException, BuildFileParseException, IOException {
    parseTargetForXCellVisibility("//sub:wild-card");
  }

  private void parseTargetForXCellVisibility(String targetName)
      throws IOException, InterruptedException, BuildFileParseException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/visibility/primary", "inter-cell/visibility/secondary");

    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    registerCell(primary, "primary", primary);
    registerCell(secondary, "primary", primary);

    // We could just do a build, but that's a little extreme since all we need is the target graph
    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownBuildRuleTypesProvider knownBuildRuleTypesProvider =
        KnownBuildRuleTypesProvider.of(
            DefaultKnownBuildRuleTypesFactory.of(
                processExecutor, pluginManager, new TestSandboxExecutionStrategyFactory()));
    ParserConfig parserConfig = primary.asCell().getBuckConfig().getView(ParserConfig.class);
    KnownConfigurationRuleTypes knownConfigurationRuleTypes =
        PluginBasedKnownConfigurationRuleTypesFactory.createFromPlugins(pluginManager);
    TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory();
    Parser parser =
        new DefaultParser(
            new PerBuildStateFactory(
                coercerFactory,
                new ConstructorArgMarshaller(coercerFactory),
                knownBuildRuleTypesProvider,
                knownConfigurationRuleTypes,
                new ParserPythonInterpreterProvider(parserConfig, new ExecutableFinder())),
            parserConfig,
            coercerFactory,
            new TargetSpecResolver());
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();

    Cell primaryCell = primary.asCell();
    BuildTarget namedTarget =
        BuildTargetFactory.newInstance(primaryCell.getFilesystem().getRootPath(), targetName);

    // It's enough that this parses cleanly.
    parser.buildTargetGraph(
        eventBus,
        primaryCell,
        false,
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
        ImmutableSet.of(namedTarget));
  }

  @Test
  @Ignore
  public void allOutputsShouldBePlacedInTheSameRootOutputFolder() {}

  @Test
  public void circularCellReferencesAreAllowed() throws IOException {
    ProjectWorkspace mainRepo =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "inter-cell/circular", tmp);
    mainRepo.setUp();
    Path primary = mainRepo.getPath("primary");

    ProcessResult result =
        mainRepo.runBuckCommandWithEnvironmentOverridesAndContext(
            primary, Optional.empty(), ImmutableMap.of(), "build", "//:bin");

    result.assertSuccess();
  }

  @Test
  public void testCommandLineConfigOverridesShouldSucceed() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("cxx", ImmutableMap.of("cc", "/does/not/exist")));

    ProcessResult result = primary.runBuckBuild("--config", "secondary//cxx.cc=", "//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void testCommandLineConfigOverridesShouldFail() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("cxx", ImmutableMap.of("cc", "/does/not/exist")));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Overridden cxx:cc path not found: /does/not/exist\n\n"
            + "This error happened while trying to get dependency 'secondary//:cxxlib' of target '//:cxxbinary'");
    // This should throw
    primary.runBuckBuild("//:cxxbinary");
  }

  @Test
  public void testCommandLineConfigOverridesWithConfigFile() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("cxx", ImmutableMap.of("cc", "/does/not/exist")));

    Path arg = tmp.newFile("buckconfig");
    Files.write(arg, ImmutableList.of("[cxx]", "  cc ="));

    ProcessResult result =
        primary.runBuckBuild("--config-file", "secondary//=" + arg, "//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void globalCommandLineConfigOverridesShouldWork() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        primary, ImmutableMap.of("cxx", ImmutableMap.of("cc", "/does/not/exist")));
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("cxx", ImmutableMap.of("cc", "/does/not/exist")));

    ProcessResult result = primary.runBuckBuild("--config", "cxx.cc=", "//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void buildFilesCanIncludeDefsFromOtherCells() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace root = createWorkspace("inter-cell/include-defs/root");
    ProjectWorkspace other = createWorkspace("inter-cell/include-defs/other");
    registerCell(root, "other", other);
    registerCell(root, "root", root);
    registerCell(other, "root", root);

    root.runBuckBuild("//:rule", "other//:rule").assertSuccess();
  }

  @Test
  public void buildFilesCanIncludeDefsFromOtherCellsUsingImplicitIncludes() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace root = createWorkspace("inter-cell/includes/root");
    ProjectWorkspace other = createWorkspace("inter-cell/includes/other");
    registerCell(root, "other", other);
    registerCell(root, "root", root);
    registerCell(other, "root", root);
    TestDataHelper.overrideBuckconfig(
        root, ImmutableMap.of("buildfile", ImmutableMap.of("includes", "other//DEFS")));

    root.runBuckBuild("//:rule", "other//:rule").assertSuccess();
  }

  @Test
  public void shouldBeAbleToTestACxxLibrary() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    ProjectWorkspace workspace = createWorkspace("inter-cell/gtest/secondary");
    TestDataHelper.overrideBuckconfig(
        workspace, ImmutableMap.of("cxx", ImmutableMap.of("gtest_dep", "//gtest:gtest")));

    ProcessResult result = workspace.runBuckBuild("//test:cxxtest");
    result.assertSuccess();

    result = workspace.runBuckCommand("test", "//test:cxxtest");
    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToTestACxxLibraryXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/gtest/primary", "inter-cell/gtest/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("cxx", ImmutableMap.of("gtest_dep", "//gtest:gtest")));

    ProcessResult result = primary.runBuckBuild("secondary//test:cxxtest");
    result.assertSuccess();

    result = primary.runBuckCommand("test", "secondary//test:cxxtest");
    result.assertSuccess();

    result = primary.runBuckCommand("test", "//main:main");
    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToShareGtest() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/gtest/primary", "inter-cell/gtest/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        primary, ImmutableMap.of("cxx", ImmutableMap.of("gtest_dep", "secondary//gtest:gtest")));
    // TODO(mzlee,dwh): secondary//gtest:gtest should be //gtest:gtest or we
    // should be able to use different cell names
    registerCell(secondary, "secondary", secondary);
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("cxx", ImmutableMap.of("gtest_dep", "secondary//gtest:gtest")));

    // TODO(mzlee,dwh): //test:cxxtest should be able to safely depend on
    // secondary//lib:cxxlib instead of having its own copy
    ProcessResult result =
        primary.runBuckCommand("test", "//test:cxxtest", "secondary//test:cxxtest");
    result.assertSuccess();
  }

  @Test
  public void childCellWithCellMappingNotInRootCellShouldThrowError() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("repositories.third must exist in the root cell's cell mappings.");

    ProjectWorkspace root = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace second = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace third = createWorkspace("inter-cell/validation/root");
    registerCell(root, "second", second);
    registerCell(second, "third", third);

    // should fail since "third" is not specified in root
    root.runBuckBuild("//:dummy");
  }

  @Test
  public void childCellWithCellMappingInRootCellShouldSucceed() throws IOException {
    ProjectWorkspace root = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace second = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace third = createWorkspace("inter-cell/validation/root");
    registerCell(root, "second", second);
    registerCell(second, "third", third);

    // now that "third" is registered in root, should succeed
    registerCell(root, "third", third);
    ProcessResult result = root.runBuckBuild("//:dummy");
    result.assertSuccess();
  }

  @Test
  public void childCellWithCellMappingThatDiffersFromRootCellShouldThrowError() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "repositories.third must point to the same directory as the root cell's cell "
            + "mapping:");
    ProjectWorkspace root = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace second = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace third = createWorkspace("inter-cell/validation/root");
    registerCell(root, "second", second);
    registerCell(second, "third", third);
    registerCell(root, "third", second);

    // should fail since we mapped "third" to wrong cell
    root.runBuckBuild("//:dummy");
  }

  @Test
  public void testCrossCellAndroidLibrary() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/android/primary", "inter-cell/android/secondary");
    ProjectWorkspace primary = cells.getFirst();

    String target = "//apps/sample:app_with_cross_cell_android_lib";
    ProcessResult result = primary.runBuckCommand("build", target);
    result.assertSuccess();
  }

  @Test
  public void testCrossCellAndroidLibraryMerge() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/android/primary", "inter-cell/android/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        primary, ImmutableMap.of("ndk", ImmutableMap.of("cpu_abis", "x86")));
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("ndk", ImmutableMap.of("cpu_abis", "x86")));

    NdkCxxPlatform platform = AndroidNdkHelper.getNdkCxxPlatform(primary.asCell().getFilesystem());
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    Path tmpDir = tmp.newFolder("merging_tmp");
    SymbolGetter syms =
        new SymbolGetter(
            new DefaultProcessExecutor(new TestConsole()),
            tmpDir,
            platform.getObjdump(),
            pathResolver);
    SymbolsAndDtNeeded info;
    Path apkPath = primary.buildAndReturnOutput("//apps/sample:app_with_merged_cross_cell_libs");

    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1g.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1h.so");

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1.so");
    assertThat(info.symbols.global, Matchers.hasItem("A"));
    assertThat(info.symbols.global, Matchers.hasItem("B"));
    assertThat(info.symbols.global, Matchers.hasItem("G"));
    assertThat(info.symbols.global, Matchers.hasItem("H"));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_B.so")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libmerge_G.so")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libmerge_H.so")));
  }

  @Test
  public void testCrossCellDependencyMerge() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/android/primary", "inter-cell/android/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        primary, ImmutableMap.of("ndk", ImmutableMap.of("cpu_abis", "x86")));
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("ndk", ImmutableMap.of("cpu_abis", "x86")));

    NdkCxxPlatform platform = AndroidNdkHelper.getNdkCxxPlatform(primary.asCell().getFilesystem());
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    Path tmpDir = tmp.newFolder("merging_tmp");
    SymbolGetter syms =
        new SymbolGetter(
            new DefaultProcessExecutor(new TestConsole()),
            tmpDir,
            platform.getObjdump(),
            pathResolver);
    SymbolsAndDtNeeded info;
    Path apkPath = primary.buildAndReturnOutput("//apps/sample:app_with_merged_cross_cell_deps");

    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1g.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1h.so");
    zipInspector.assertFileExists("lib/x86/libI.so");

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1.so");
    assertThat(info.symbols.global, Matchers.hasItem("A"));
    assertThat(info.symbols.global, Matchers.hasItem("B"));
    assertThat(info.symbols.global, Matchers.hasItem("G"));
    assertThat(info.symbols.global, Matchers.hasItem("H"));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_B.so")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libmerge_G.so")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libmerge_H.so")));
  }

  @Test
  public void targetsReferencingSameTargetsWithDifferentCellNamesAreConsideredTheSame()
      throws Exception {
    // This test case builds a cxx binary rule with libraries that all depend on the same targets.
    // If these targets were treated as distinct targets, the rule will have duplicate symbols.
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/canonicalization/primary", "inter-cell/canonicalization/secondary");

    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    registerCell(primary, "primary", primary);
    registerCell(secondary, "primary", primary);

    Path output = primary.buildAndReturnOutput(":a.out");
    assertEquals(
        "The produced binary should give the expected exit code",
        111,
        primary.runCommand(output.toString()).getExitCode());
  }

  @Test
  public void targetsInOtherCellsArePrintedAsRelativeToRootCell() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/canonicalization/primary", "inter-cell/canonicalization/secondary");

    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    registerCell(primary, "primary", primary);
    registerCell(secondary, "primary", primary);

    String queryResult =
        primary.runBuckCommand("query", "deps(//:a.out)").assertSuccess().getStdout();
    assertEquals(
        "Should refer to root cell targets without prefix and secondary cell targets with prefix",
        Joiner.on("\n").join("//:a.out", "//:rootlib", "secondary//:lib", "secondary//:lib2"),
        sortLines(queryResult));

    queryResult =
        primary.runBuckCommand("query", "deps(secondary//:lib)").assertSuccess().getStdout();
    assertEquals(
        "... even if query starts in a non-root cell.",
        Joiner.on("\n").join("//:rootlib", "secondary//:lib", "secondary//:lib2"),
        sortLines(queryResult));
  }

  @Test
  public void testCrossCellCleanCommand() throws IOException, InterruptedException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/export-file/primary", "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    List<Path> primaryDirs =
        ImmutableList.of(
            primary.getPath(primary.getBuckPaths().getScratchDir()),
            primary.getPath(primary.getBuckPaths().getGenDir()),
            primary.getPath(primary.getBuckPaths().getTrashDir()));
    List<Path> secondaryDirs =
        ImmutableList.of(
            secondary.getPath(secondary.getBuckPaths().getScratchDir()),
            secondary.getPath(secondary.getBuckPaths().getGenDir()),
            secondary.getPath(secondary.getBuckPaths().getTrashDir()));

    // Set up the directories to be cleaned
    for (Path dir : primaryDirs) {
      Files.createDirectories(dir);
      assertTrue(Files.exists(dir));
    }
    for (Path dir : secondaryDirs) {
      Files.createDirectories(dir);
      assertTrue(Files.exists(dir));
    }

    primary.runBuckCommand("clean", "--keep-cache").assertSuccess();

    for (Path dir : primaryDirs) {
      assertFalse(Files.exists(dir));
    }
    for (Path dir : secondaryDirs) {
      assertFalse(Files.exists(dir));
    }
  }

  @Test
  public void testParserFunctionsWithCells() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/parser-functions/primary", "inter-cell/parser-functions/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    // Set up the remaining cells
    registerCell(primary, "primary", primary);
    registerCell(secondary, "primary", primary);
    registerCell(secondary, "secondary", secondary);

    String expected = primary.getFileContents("one/.txt");
    Path path = primary.buildAndReturnOutput("//one:one");
    String actual = new String(Files.readAllBytes(path), UTF_8);
    assertEquals(expected, actual);

    expected = secondary.getFileContents("two/secondary.txt");
    path = primary.buildAndReturnOutput("//one:two");
    actual = new String(Files.readAllBytes(path), UTF_8);
    assertEquals(expected, actual);

    expected = primary.getFileContents("one/primary.txt");
    path = secondary.buildAndReturnOutput("//two:one");
    actual = new String(Files.readAllBytes(path), UTF_8);
    assertEquals(expected, actual);

    expected = secondary.getFileContents("two/.txt");
    path = secondary.buildAndReturnOutput("//two:two");
    actual = new String(Files.readAllBytes(path), UTF_8);
    assertEquals(expected, actual);
  }

  @Test
  public void testCrossCellHandlesSQLiteMetadataConsistently() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    // This test case verifies that building the same target from different cells doesn't cause the
    // SQLite metadata store and filesystem to get out of sync.
    Pair<ProjectWorkspace, ProjectWorkspace> cells =
        prepare("inter-cell/metadata/primary", "inter-cell/metadata/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    // Set up the remaining cells
    registerCell(primary, "primary", primary);
    registerCell(secondary, "primary", primary);
    registerCell(secondary, "secondary", secondary);

    TestDataHelper.overrideBuckconfig(
        primary, ImmutableMap.of("build", ImmutableMap.of("metadata_storage", "sqlite")));
    TestDataHelper.overrideBuckconfig(
        secondary, ImmutableMap.of("build", ImmutableMap.of("metadata_storage", "sqlite")));

    // Build in the root cell.  This populates the db with //:hello
    // entries, and the filesystem with RECORDED_PATHS metadata at paths like:
    // bin/.hello/metadata/RECORDED_PATHS
    primary.runBuckBuild("//:hello", "-c", "build.metadata_storage=sqlite").assertSuccess();

    // Build in the secondary cell.  This should overwrite the cell1 db with entries like //:hello,
    // and overwrite the filesystem with bin/.hello/metadata/RECORDED_PATHS.  Note: it's important
    // that the DB keys are the same as in the previous step!  If we use fully-qualified target
    // names, the rows can get out of sync.
    secondary.runBuckBuild("primary//:hello").assertSuccess();

    // Introduce changes to both files, including a compile error, and build again.  The compiler
    // error will cause
    // the build to delete the //:hello rows from the DB, and delete .hello/metadata/RECORDED_PATHS
    String hello = primary.getFileContents("hello.cpp");
    String main = primary.getFileContents("main.cpp");
    primary.writeContentsToPath(hello + "compile error\n", "hello.cpp");
    primary.writeContentsToPath(main + "// foo\n", "main.cpp");

    primary.runBuckBuild("//:hello").assertFailure();

    // Revert the compile error, and build in the secondary.
    primary.writeContentsToPath(hello, "hello.cpp");
    secondary.runBuckBuild("primary//:hello").assertSuccess();
  }

  private static String sortLines(String input) {
    return RichStream.from(Splitter.on('\n').trimResults().omitEmptyStrings().split(input))
        .sorted()
        .collect(Collectors.joining("\n"));
  }

  private Pair<ProjectWorkspace, ProjectWorkspace> prepare(String primaryPath, String secondaryPath)
      throws IOException {

    ProjectWorkspace primary = createWorkspace(primaryPath);
    ProjectWorkspace secondary = createWorkspace(secondaryPath);

    registerCell(primary, "secondary", secondary);

    return new Pair<>(primary, secondary);
  }

  private ProjectWorkspace createWorkspace(String scenarioName) throws IOException {
    Path tmpSubfolder = tmp.newFolder();
    ProjectWorkspace projectWorkspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, scenarioName, tmpSubfolder);
    projectWorkspace.setUp();
    return projectWorkspace;
  }

  private void registerCell(
      ProjectWorkspace cellToModifyConfigOf,
      String cellName,
      ProjectWorkspace cellToRegisterAsCellName)
      throws IOException {
    TestDataHelper.overrideBuckconfig(
        cellToModifyConfigOf,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of(
                cellName, cellToRegisterAsCellName.getPath(".").normalize().toString())));
  }
}
