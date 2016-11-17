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

import static com.facebook.buck.io.MorePaths.asPaths;
import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hashing;

import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reports targets that own a specified list of files.
 */
public class OwnersReportTest {

  public static class FakeDescription implements Description<FakeDescription.FakeArg> {

    @Override
    public BuildRuleType getBuildRuleType() {
      return BuildRuleType.of("fake_rule");
    }

    @Override
    public FakeArg createUnpopulatedConstructorArg() {
      return new FakeArg();
    }

    @Override
    public <A extends FakeArg> BuildRule createBuildRule(
        TargetGraph targetGraph,
        BuildRuleParams params,
        BuildRuleResolver resolver,
        A args) {
      return new FakeBuildRule(params, new SourcePathResolver(resolver));
    }

    public static class FakeArg extends AbstractDescriptionArg {
      public ImmutableSet<Path> inputs;
    }
  }

  private static TargetNode<?, ?> createTargetNode(
      BuildTarget buildTarget,
      ImmutableSet<Path> inputs) {
    Description<FakeDescription.FakeArg> description = new FakeDescription();
    FakeDescription.FakeArg arg = description.createUnpopulatedConstructorArg();
    arg.inputs = inputs;
    try {
      FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
      return
          new TargetNodeFactory(new DefaultTypeCoercerFactory(ObjectMappers.newDefaultInstance()))
              .create(
                  Hashing.sha1().hashString(buildTarget.getFullyQualifiedName(), UTF_8),
                  description,
                  arg,
                  filesystem,
                  buildTarget,
                  ImmutableSet.of(),
                  ImmutableSet.of(),
                  createCellRoots(filesystem));
    } catch (NoSuchBuildTargetException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("serial")
  private static class ExistingDirectoryFile extends File {
    public ExistingDirectoryFile(Path file, String s) {
      super(file.toFile(), s);
    }

    @Override
    public boolean isFile() {
      return false;
    }

    @Override
    public boolean isDirectory() {
      return true;
    }

    @Override
    public boolean exists() {
      return true;
    }
  }

  @SuppressWarnings("serial")
  private static class MissingFile extends File {
    public MissingFile(Path file, String s) {
      super(file.toFile(), s);
    }

    @Override
    public boolean exists() {
      return false;
    }

    @Override
    public boolean isFile() {
      return true;
    }

    @Override
    public boolean isDirectory() {
      return false;
    }
  }

  @SuppressWarnings("serial")
  private static class ExistingFile extends File {
    public ExistingFile(Path file, String s) {
      super(file.toFile(), s);
    }

    @Override
    public boolean exists() {
      return true;
    }

    @Override
    public boolean isFile() {
      return true;
    }

    @Override
    public boolean isDirectory() {
      return false;
    }
  }

  @Test
  public void verifyPathsThatAreNotFilesAreCorrectlyReported()
      throws CmdLineException, IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingDirectoryFile(getRootPath(), pathRelativeToProjectRoot);
      }
    };


    // Inputs that should be treated as "non-files", i.e. as directories
    ImmutableSet<String> inputs = ImmutableSet.of(
        "java/somefolder/badfolder",
        "java/somefolder",
        "com/test/subtest");

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?, ?> targetNode = createTargetNode(target, ImmutableSet.of());

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(
        cell,
        targetNode,
        inputs);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());
    assertEquals(inputs, report.nonFileInputs);
  }

  @Test
  public void verifyMissingFilesAreCorrectlyReported()
      throws CmdLineException, IOException, InterruptedException {
    // All files will be directories now
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new MissingFile(getRootPath(), pathRelativeToProjectRoot);
      }
    };

    // Inputs that should be treated as missing files
    ImmutableSet<String> inputs = ImmutableSet.of(
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java");

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?, ?> targetNode = createTargetNode(target, ImmutableSet.of());

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(cell, targetNode, inputs);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());
    assertEquals(inputs, report.nonExistentInputs);
  }

  @Test
  public void verifyInputsWithoutOwnersAreCorrectlyReported()
      throws CmdLineException, IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingFile(getRootPath(), pathRelativeToProjectRoot);
      }
    };

    // Inputs that should be treated as existing files
    ImmutableSet<String> inputs = ImmutableSet.of(
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java");
    ImmutableSet<Path> inputPaths = asPaths(inputs);

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?, ?> targetNode = createTargetNode(target, ImmutableSet.of());

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(
        cell,
        targetNode,
        inputs);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertEquals(inputPaths, report.inputsWithNoOwners);
  }

  @Test
  public void verifyInputsAgainstRulesThatListDirectoryInputs()
      throws IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingFile(getRootPath(), pathRelativeToProjectRoot);
      }
    };

    // Inputs that should be treated as existing files
    ImmutableSet<String> inputs = ImmutableSet.of(
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java");
    ImmutableSet<Path> inputPaths = asPaths(inputs);

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?, ?> targetNode = createTargetNode(
        target,
        ImmutableSet.of(Paths.get("java/somefolder")));

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(
        cell,
        targetNode,
        inputs);
    assertTrue(report.owners.containsKey(targetNode));
    assertEquals(inputPaths, report.owners.get(targetNode));
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());
  }

  /**
   * Verify that owners are correctly detected:
   *  - one owner, multiple inputs
   */
  @Test
  public void verifyInputsWithOneOwnerAreCorrectlyReported()
      throws CmdLineException, IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingFile(getRootPath(), pathRelativeToProjectRoot);
      }
    };

    ImmutableSet<String> inputs = ImmutableSet.of(
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java");
    ImmutableSet<Path> inputPaths = asPaths(inputs);

    BuildTarget target = BuildTargetFactory.newInstance("//base:name");
    TargetNode<?, ?> targetNode = createTargetNode(target, inputPaths);

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.generateOwnersReport(cell, targetNode, inputs);
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());

    assertEquals(inputs.size(), report.owners.size());
    assertTrue(report.owners.containsKey(targetNode));
    assertEquals(targetNode.getInputs(), report.owners.get(targetNode));
  }

  /**
   * Verify that owners are correctly detected:
   *  - inputs that belong to multiple targets
   */
  @Test
  public void verifyInputsWithMultipleOwnersAreCorrectlyReported()
      throws CmdLineException, IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingFile(getRootPath(), pathRelativeToProjectRoot);
      }
    };

    ImmutableSet<String> inputs = ImmutableSet.of(
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java");
    ImmutableSortedSet<Path> inputPaths = asPaths(inputs);

    BuildTarget target1 = BuildTargetFactory.newInstance("//base/name1:name");
    BuildTarget target2 = BuildTargetFactory.newInstance("//base/name2:name");
    TargetNode<?, ?> targetNode1 = createTargetNode(target1, inputPaths);
    TargetNode<?, ?> targetNode2 = createTargetNode(target2, inputPaths);

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    OwnersReport report = OwnersReport.emptyReport();
    report = report.updatedWith(
        OwnersReport.generateOwnersReport(cell, targetNode1, inputs));
    report = report.updatedWith(
        OwnersReport.generateOwnersReport(cell, targetNode2, inputs));

    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());

    assertTrue(report.owners.containsKey(targetNode1));
    assertTrue(report.owners.containsKey(targetNode2));
    assertEquals(targetNode1.getInputs(), report.owners.get(targetNode1));
    assertEquals(targetNode2.getInputs(), report.owners.get(targetNode2));
  }

}
