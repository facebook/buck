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

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.MoreExecutors;
import com.martiansoftware.nailgun.NGContext;

import org.ini4j.Ini;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;

public class InterCellIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void ensureThatNormalBuildsWorkAsExpected() throws IOException {
    ProjectWorkspace secondary = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "inter-cell/export-file/secondary",
        tmp);
    secondary.setUp();

    ProjectWorkspace.ProcessResult result = secondary.runBuckBuild("//:hello");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseAnExportFileXRepoTarget() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    String expected = secondary.getFileContents("hello.txt");
    Path path = primary.buildAndReturnOutput("//:exported-file");

    String actual = new String(Files.readAllBytes(path), UTF_8);

    assertEquals(expected, actual);
  }

  @Test
  public void shouldBeAbleToUseTargetsCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace.ProcessResult result = primary.runBuckCommand(
        "targets",
        "--show-target-hash",
        "//:cxxbinary");
    result.assertSuccess();

    ProjectWorkspace.ProcessResult result2 = primary.runBuckCommand(
        "targets",
        "secondary//:cxxlib");
    result2.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseQueryCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace.ProcessResult result = primary.runBuckCommand(
        "query",
        "deps(%s)",
        "//:cxxbinary");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(primary.getFileContents("stdout-cross-cell-dep")));
  }

  @Test
  public void shouldBeAbleToUseProjectCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();

    ProjectWorkspace.ProcessResult result = primary.runBuckCommand("project", "//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseACxxLibraryXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();

    ProjectWorkspace.ProcessResult result = primary.runBuckBuild("//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseMultipleXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace primary = createWorkspace("inter-cell/multi-cell/primary");
    primary.setUp();
    ProjectWorkspace secondary = createWorkspace("inter-cell/multi-cell/secondary");
    secondary.setUp();
    ProjectWorkspace ternary = createWorkspace("inter-cell/multi-cell/ternary");
    ternary.setUp();
    registerCell(secondary, "ternary", ternary);
    registerCell(primary, "secondary", secondary);

    primary.runBuckCommand("targets", "--show-target-hash", "//:cxxbinary");
    secondary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib");
    ternary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib2");

    ProjectWorkspace.ProcessResult result = primary.runBuckBuild("//:cxxbinary");
    result.assertSuccess();
  }

  @Test
  public void xCellCxxLibraryBuildsShouldBeHermetic() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    Path firstBinary = primary.buildAndReturnOutput("//:cxxbinary");
    ImmutableMap<String, HashCode> firstPrimaryObjectFiles = findObjectFiles(primary);
    ImmutableMap<String, HashCode> firstObjectFiles = findObjectFiles(secondary);

        // Now recreate an identical checkout
    cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    primary = cells.getFirst();
    secondary = cells.getSecond();

    Path secondBinary = primary.buildAndReturnOutput("//:cxxbinary");
    ImmutableMap<String, HashCode> secondPrimaryObjectFiles = findObjectFiles(primary);
    ImmutableMap<String, HashCode> secondObjectFiles = findObjectFiles(secondary);

    assertEquals(firstPrimaryObjectFiles, secondPrimaryObjectFiles);
    assertEquals(firstObjectFiles, secondObjectFiles);
    MoreAsserts.assertContentsEqual(firstBinary, secondBinary);
  }

  private ImmutableMap<String, HashCode> findObjectFiles(final ProjectWorkspace workspace)
      throws IOException {
    final Path buckOut = workspace.getPath(BuckConstant.getBuckOutputDirectory());

    final ImmutableMap.Builder<String, HashCode> objectHashCodes = ImmutableMap.builder();
    Files.walkFileTree(buckOut, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
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
  @Ignore
  public void shouldBeAbleToUseAJavaLibraryTargetXCell() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/java/primary",
        "inter-cell/java/secondary");
    ProjectWorkspace primary = cells.getFirst();
    registerCell(cells.getSecond(), "primary", primary);

    ProjectWorkspace.ProcessResult result = primary.runBuckBuild("//:java-binary", "-v", "5");

    result.assertSuccess();
  }

  @Test
  public void buildFileNamesCanBeDifferentCrossCell() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/build-file-names/primary",
        "inter-cell/build-file-names/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    Path output = primary.buildAndReturnOutput("//:export");
    String expected = secondary.getFileContents("hello-world.txt");

    assertEquals(expected, new String(Files.readAllBytes(output), UTF_8));
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void xCellVisibilityShouldWorkAsExpected()
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    try {
      parseTargetForXCellVisibility("//:not-visible-target");
      fail("Did not expect parsing to succeed");
    } catch (HumanReadableException expected) {
      // Everything is as it should be.
    }
  }

  @Test
  public void xCellVisibilityPatternsBasedOnExplicitBuildTargetsWork()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    parseTargetForXCellVisibility("//:visible-target");
  }

  @Test
  public void xCellSingleDirectoryVisibilityPatternsWork()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    parseTargetForXCellVisibility("//sub2:directory");
  }

  @Test
  public void xCellSubDirectoryVisibilityPatternsWork()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    parseTargetForXCellVisibility("//sub:wild-card");
  }

  private void parseTargetForXCellVisibility(String targetName)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/visibility/primary",
        "inter-cell/visibility/secondary");

    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    registerCell(secondary, "primary", primary);

    // We could just do a build, but that's a little extreme since all we need is the target graph
    TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory(
        ObjectMappers.newDefaultInstance());
    Parser parser = new Parser(
        new ParserConfig(cells.getFirst().asCell().getBuckConfig()),
        coercerFactory,
        new ConstructorArgMarshaller(coercerFactory));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    Cell primaryCell = primary.asCell();
    BuildTarget namedTarget = BuildTargetFactory.newInstance(
        primaryCell.getFilesystem(),
        targetName);

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
  public void allOutputsShouldBePlacedInTheSameRootOutputFolder() {

  }

  @Test
  public void circularCellReferencesAreAllowed() throws IOException {
    ProjectWorkspace mainRepo = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "inter-cell/circular",
        tmp);
    mainRepo.setUp();
    Path primary = mainRepo.getPath("primary");

    ProjectWorkspace.ProcessResult result = mainRepo.runBuckCommandWithEnvironmentAndContext(
        primary,
        Optional.<NGContext>absent(),
        Optional.<BuckEventListener>absent(),
        Optional.<ImmutableMap<String, String>>absent(),
        "build",
        "//:bin");

    result.assertSuccess();
  }

  private Pair<ProjectWorkspace, ProjectWorkspace> prepare(
      String primaryPath,
      String secondaryPath) throws IOException {

    ProjectWorkspace primary = createWorkspace(primaryPath);
    primary.setUp();

    ProjectWorkspace secondary = createWorkspace(secondaryPath);
    secondary.setUp();

    registerCell(primary, "secondary", secondary);

    return new Pair<>(primary, secondary);
  }

  private ProjectWorkspace createWorkspace(String scenarioName) throws IOException {
    Path templateDir = TestDataHelper.getTestDataScenario(this, scenarioName);
    return new ProjectWorkspace(
        templateDir,
        tmp.newFolder());
  }

  private void registerCell(
      ProjectWorkspace cellToModifyConfigOf,
      String cellName,
      ProjectWorkspace cellToRegisterAsCellName) throws IOException {
    String config = cellToModifyConfigOf.getFileContents(".buckconfig");
    Ini ini = new Ini(new StringReader(config));
    ini.put("repositories", cellName, cellToRegisterAsCellName.getPath(".").normalize());
    StringWriter writer = new StringWriter();
    ini.store(writer);
    Files.write(cellToModifyConfigOf.getPath(".buckconfig"), writer.toString().getBytes(UTF_8));
  }
}
