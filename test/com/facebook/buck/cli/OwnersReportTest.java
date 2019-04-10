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

package com.facebook.buck.cli;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.impl.FilesystemBackedBuildFileTree;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TestParserFactory;
import com.facebook.buck.parser.TestPerBuildStateFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Test;

/** Reports targets that own a specified list of files. */
public class OwnersReportTest {

  public static class FakeRuleDescription
      implements DescriptionWithTargetGraph<FakeRuleDescriptionArg> {

    @Override
    public Class<FakeRuleDescriptionArg> getConstructorArgType() {
      return FakeRuleDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        FakeRuleDescriptionArg args) {
      return new FakeBuildRule(buildTarget, context.getProjectFilesystem(), params);
    }

    @BuckStyleImmutable
    @Value.Immutable
    interface AbstractFakeRuleDescriptionArg extends CommonDescriptionArg {
      ImmutableSet<Path> getInputs();
    }
  }

  private TargetNode<?> createTargetNode(BuildTarget buildTarget, ImmutableSet<Path> inputs) {
    FakeRuleDescription description = new FakeRuleDescription();
    FakeRuleDescriptionArg arg =
        FakeRuleDescriptionArg.builder()
            .setName(buildTarget.getShortName())
            .setInputs(inputs)
            .build();
    try {
      return new TargetNodeFactory(new DefaultTypeCoercerFactory())
          .createFromObject(
              description,
              arg,
              filesystem,
              buildTarget,
              ImmutableSet.of(),
              ImmutableSet.of(),
              ImmutableSet.of(),
              createCellRoots(filesystem));
    } catch (NoSuchBuildTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  @Test
  public void verifyPathsThatAreNotFilesAreCorrectlyReported() throws IOException {
    filesystem.mkdirs(filesystem.getPath("java/somefolder/badfolder"));
    filesystem.mkdirs(filesystem.getPath("com/test/subtest"));

    // Inputs that should be treated as "non-files", i.e. as directories
    String input = "java/somefolder/badfolder";

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?> targetNode = createTargetNode(target, ImmutableSet.of());

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(cell, targetNode, input);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());
    assertEquals(ImmutableSet.of(input), report.nonFileInputs);
  }

  @Test
  public void verifyMissingFilesAreCorrectlyReported() {
    // Inputs that should be treated as missing files
    String input = "java/somefolder/badfolder/somefile.java";

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?> targetNode = createTargetNode(target, ImmutableSet.of());

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(cell, targetNode, input);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());
    assertEquals(ImmutableSet.of(input), report.nonExistentInputs);
  }

  @Test
  public void verifyInputsWithoutOwnersAreCorrectlyReported() throws IOException {
    // Inputs that should be treated as existing files
    String input = "java/somefolder/badfolder/somefile.java";
    Path inputPath = filesystem.getPath(input);

    // Write dummy files.
    filesystem.mkdirs(inputPath.getParent());
    filesystem.writeContentsToPath("", inputPath);

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?> targetNode = createTargetNode(target, ImmutableSet.of());

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(cell, targetNode, input);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertEquals(ImmutableSet.of(inputPath), report.inputsWithNoOwners);
  }

  @Test
  public void verifyInputsAgainstRulesThatListDirectoryInputs() throws IOException {
    // Inputs that should be treated as existing files
    String input = "java/somefolder/badfolder/somefile.java";
    Path inputPath = filesystem.getPath(input);

    // Write dummy files.
    filesystem.mkdirs(inputPath.getParent());
    filesystem.writeContentsToPath("", inputPath);

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?> targetNode =
        createTargetNode(target, ImmutableSet.of(filesystem.getPath("java/somefolder")));

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(cell, targetNode, input);
    assertTrue(report.owners.containsKey(targetNode));
    assertEquals(ImmutableSet.of(inputPath), report.owners.get(targetNode));
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());
  }

  /** Verify that owners are correctly detected: - one owner, multiple inputs */
  @Test
  public void verifyInputsWithOneOwnerAreCorrectlyReported() throws IOException {

    ImmutableList<String> inputs =
        ImmutableList.of("java/somefolder/badfolder/somefile.java", "java/somefolder/perfect.java");
    ImmutableSet<Path> inputPaths =
        RichStream.from(inputs).map(filesystem::getPath).toImmutableSet();

    for (Path path : inputPaths) {
      filesystem.mkdirs(path.getParent());
      filesystem.writeContentsToPath("", path);
    }

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?> targetNode = createTargetNode(target, inputPaths);

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report1 = OwnersReport.generateOwnersReport(cell, targetNode, inputs.get(0));
    OwnersReport report2 = OwnersReport.generateOwnersReport(cell, targetNode, inputs.get(1));
    OwnersReport report = report1.updatedWith(report2);

    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());

    assertEquals(inputs.size(), report.owners.size());
    assertTrue(report.owners.containsKey(targetNode));
    assertEquals(targetNode.getInputs(), report.owners.get(targetNode));
  }

  /** Verify that owners are correctly detected: - inputs that belong to multiple targets */
  @Test
  public void verifyInputsWithMultipleOwnersAreCorrectlyReported() throws IOException {
    String input = "java/somefolder/badfolder/somefile.java";
    Path inputPath = filesystem.getPath(input);

    filesystem.mkdirs(inputPath.getParent());
    filesystem.writeContentsToPath("", inputPath);

    BuildTarget target1 = BuildTargetFactory.newInstance("//base/name1:name");
    BuildTarget target2 = BuildTargetFactory.newInstance("//base/name2:name");
    TargetNode<?> targetNode1 = createTargetNode(target1, ImmutableSet.of(inputPath));
    TargetNode<?> targetNode2 = createTargetNode(target2, ImmutableSet.of(inputPath));

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(cell, targetNode1, input);
    report = report.updatedWith(OwnersReport.generateOwnersReport(cell, targetNode2, input));

    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());

    assertTrue(report.owners.containsKey(targetNode1));
    assertTrue(report.owners.containsKey(targetNode2));
    assertEquals(targetNode1.getInputs(), report.owners.get(targetNode1));
    assertEquals(targetNode2.getInputs(), report.owners.get(targetNode2));
  }

  @Test
  public void verifyThatRequestedFilesThatDoNotExistOnDiskAreReported() {
    String input = "java/some_file";

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    Parser parser = TestParserFactory.create(cell);
    OwnersReport report =
        OwnersReport.builder(
                cell,
                TestParserFactory.create(cell),
                TestPerBuildStateFactory.create(parser, cell),
                EmptyTargetConfiguration.INSTANCE)
            .build(getBuildFileTrees(cell), ImmutableSet.of(input));

    assertEquals(1, report.nonExistentInputs.size());
    assertTrue(report.nonExistentInputs.contains(MorePaths.pathWithPlatformSeparators(input)));
  }

  private ImmutableMap<Cell, BuildFileTree> getBuildFileTrees(Cell rootCell) {
    return rootCell.getAllCells().stream()
        .collect(
            ImmutableMap.toImmutableMap(
                Function.identity(),
                cell ->
                    new FilesystemBackedBuildFileTree(
                        cell.getFilesystem(),
                        cell.getBuckConfigView(ParserConfig.class).getBuildFileName())));
  }
}
