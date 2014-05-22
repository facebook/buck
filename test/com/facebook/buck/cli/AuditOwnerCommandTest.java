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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypes;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.base.Joiner;

import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Outputs targets that own a specified list of files.
 */
public class AuditOwnerCommandTest {
  private TestConsole console;

  private static class StubBuildRule implements BuildRule {

    private final ImmutableSet<Path> inputs;
    private final BuildTarget target;

    public StubBuildRule(BuildTarget target, Iterable<Path> inputs) {
      this.target = target;
      this.inputs = ImmutableSet.copyOf(inputs);
    }

    @Override
    public Iterable<Path> getInputs() {
      return inputs;
    }

    @Override
    public BuildTarget getBuildTarget() {
      return target;
    }

    @Override
    public String getFullyQualifiedName() {
      return target.getFullyQualifiedName();
    }

    @Override
    public BuildRuleType getType() {
      throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public Buildable getBuildable() {
      return null;
    }

    @Override
    public BuildableProperties getProperties() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSortedSet<BuildRule> getDeps() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isVisibleTo(BuildTarget target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public final RuleKey getRuleKey() {
      throw new UnsupportedOperationException();
    }

    @Override
    public final RuleKey getRuleKeyWithoutDeps() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(HasBuildTarget other) {
      return this.getBuildTarget().compareTo(other.getBuildTarget());
    }
  }

  private static class FakeProjectFilesystem extends ProjectFilesystem {
    public FakeProjectFilesystem() {
      super(new File("."));
    }
  }

  @SuppressWarnings("serial")
  private static class ExistingDirectoryFile extends File {
    public ExistingDirectoryFile(File file, String s) {
      super(file, s);
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
    public MissingFile(File file, String s) {
      super(file, s);
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
    public ExistingFile(File file, String s) {
      super(file, s);
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

  private BuckConfig buckConfig;

  @Before
  public void setUp() {
    console = new TestConsole();
    buckConfig = new FakeBuckConfig();
  }

  private AuditOwnerOptions getOptions(String... args) throws CmdLineException {
    AuditOwnerOptions options = new AuditOwnerOptions(buckConfig);
    new CmdLineParser(options).parseArgument(args);
    return options;
  }

  private AuditOwnerCommand createAuditOwnerCommand(ProjectFilesystem filesystem) {
    KnownBuildRuleTypes buildRuleTypes =
        DefaultKnownBuildRuleTypes.getDefaultKnownBuildRuleTypes(filesystem);
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    return new AuditOwnerCommand(new CommandRunnerParams(
        console,
        filesystem,
        androidDirectoryResolver,
        buildRuleTypes,
        new InstanceArtifactCacheFactory(artifactCache),
        eventBus,
        buckConfig.getPythonInterpreter(),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv())));
  }

  @Test
  public void verifyPathsThatAreNotFilesAreCorrectlyReported() throws CmdLineException {
    // All files will be directories now
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingDirectoryFile(getProjectRoot(), pathRelativeToProjectRoot);
      }
    };

    // Empty graph
    MutableDirectedGraph<BuildRule> mutableGraph = new MutableDirectedGraph<BuildRule>();
    DependencyGraph graph = new DependencyGraph(mutableGraph);

    // Inputs that should be treated as "non-files", i.e. as directories
    String[] args = new String[] {
        "java/somefolder/badfolder",
        "java/somefolder",
        "com/test/subtest"
    };
    ImmutableSet<String> inputs = ImmutableSet.copyOf(args);

    // Create options
    AuditOwnerOptions options = getOptions(args);

    // Create command under test
    AuditOwnerCommand command = createAuditOwnerCommand(filesystem);

    // Generate report and verify nonFileInputs are filled in as expected.
    AuditOwnerCommand.OwnersReport report = command.generateOwnersReport(graph, options);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());
    assertEquals(inputs, report.nonFileInputs);
  }

  @Test
  public void verifyMissingFilesAreCorrectlyReported() throws CmdLineException {
    // All files will be directories now
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new MissingFile(getProjectRoot(), pathRelativeToProjectRoot);
      }
    };

    // Empty graph
    MutableDirectedGraph<BuildRule> mutableGraph = new MutableDirectedGraph<BuildRule>();
    DependencyGraph graph = new DependencyGraph(mutableGraph);

    // Inputs that should be treated as missing files
    String[] args = new String[] {
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java"
    };
    ImmutableSet<String> inputs = ImmutableSet.copyOf(args);

    // Create options
    AuditOwnerOptions options = getOptions(args);

    // Create command under test
    AuditOwnerCommand command = createAuditOwnerCommand(filesystem);

    // Generate report and verify nonFileInputs are filled in as expected.
    AuditOwnerCommand.OwnersReport report = command.generateOwnersReport(graph, options);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());
    assertEquals(inputs, report.nonExistentInputs);
  }

  @Test
  public void verifyInputsWithoutOwnersAreCorrectlyReported() throws CmdLineException {
    // All files will be directories now
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingFile(getProjectRoot(), pathRelativeToProjectRoot);
      }
    };

    // Empty graph
    MutableDirectedGraph<BuildRule> mutableGraph = new MutableDirectedGraph<BuildRule>();
    DependencyGraph graph = new DependencyGraph(mutableGraph);

    // Inputs that should be treated as existing files
    String[] args = new String[] {
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java"
    };
    ImmutableSortedSet<Path> inputs = MorePaths.asPaths(Arrays.asList(args));

    // Create options
    AuditOwnerOptions options = getOptions(args);

    // Create command under test
    AuditOwnerCommand command = createAuditOwnerCommand(filesystem);

    // Generate report and verify nonFileInputs are filled in as expected.
    AuditOwnerCommand.OwnersReport report = command.generateOwnersReport(graph, options);
    assertTrue(report.owners.isEmpty());
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertEquals(inputs, report.inputsWithNoOwners);
  }

  /**
   * Verify that owners are correctly detected:
   *  - one owner, multiple inputs
   */
  @Test
  public void verifyInputsWithOneOwnerAreCorrectlyReported() throws CmdLineException {
    // All files will be directories now
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingFile(getProjectRoot(), pathRelativeToProjectRoot);
      }
    };

    // Create inputs
    String[] args = new String[] {
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java"
    };
    ImmutableSortedSet<Path> inputs = MorePaths.asPaths(ImmutableSortedSet.copyOf(args));

    // Build rule that owns all inputs
    BuildTarget target = new BuildTarget("//base/name", "name");
    BuildRule ownerRule = new StubBuildRule(target, inputs);

    // Create graph
    MutableDirectedGraph<BuildRule> mutableGraph = new MutableDirectedGraph<BuildRule>();
    mutableGraph.addNode(ownerRule);

    DependencyGraph graph = new DependencyGraph(mutableGraph);

    // Create options
    AuditOwnerOptions options = getOptions(args);

    // Create command under test
    AuditOwnerCommand command = createAuditOwnerCommand(filesystem);

    // Generate report and verify nonFileInputs are filled in as expected.
    AuditOwnerCommand.OwnersReport report = command.generateOwnersReport(graph, options);
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());

    assertEquals(inputs.size(), report.owners.size());
    assertTrue(report.owners.containsKey(ownerRule));
    assertEquals(ownerRule.getInputs(), report.owners.get(ownerRule));
  }

  /**
   * Verify that owners are correctly detected:
   *  - one owner, multiple inputs, json output
   */
  @Test
  public void verifyInputsWithOneOwnerAreCorrectlyReportedInJson()
    throws CmdLineException, IOException {
    // All files will be directories now
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingFile(getProjectRoot(), pathRelativeToProjectRoot);
      }
    };

    // Create inputs
    String[] args = new String[] {
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java"
    };
    ImmutableSortedSet<Path> inputs = MorePaths.asPaths(ImmutableSortedSet.copyOf(args));

    // Build rule that owns all inputs
    BuildTarget target = new BuildTarget("//base/name", "name");
    BuildRule ownerRule = new StubBuildRule(target, inputs);

    // Create graph
    MutableDirectedGraph<BuildRule> mutableGraph = new MutableDirectedGraph<BuildRule>();
    mutableGraph.addNode(ownerRule);

    DependencyGraph graph = new DependencyGraph(mutableGraph);

    // Create options
    AuditOwnerOptions options = getOptions(args);

    // Create command under test
    AuditOwnerCommand command = createAuditOwnerCommand(filesystem);

    // Generate report and verify nonFileInputs are filled in as expected.
    AuditOwnerCommand.OwnersReport report = command.generateOwnersReport(graph, options);
    command.printOwnersOnlyJsonReport(report);

    String expectedJson = Joiner.on("").join(
      "{",
      "\"com/test/subtest/random.java\":[\"//base/name:name\"],",
      "\"java/somefolder/badfolder/somefile.java\":[\"//base/name:name\"],",
      "\"java/somefolder/perfect.java\":[\"//base/name:name\"]",
      "}"
    );

    assertEquals(expectedJson, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  /**
   * Verify that owners are correctly detected:
   *  - inputs that belong to multiple targets
   */
  @Test
  public void verifyInputsWithMultipleOwnersAreCorrectlyReported() throws CmdLineException {
    // All files will be directories now
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public File getFileForRelativePath(String pathRelativeToProjectRoot) {
        return new ExistingFile(getProjectRoot(), pathRelativeToProjectRoot);
      }
    };

    // Create inputs
    String[] args = new String[] {
        "java/somefolder/badfolder/somefile.java",
        "java/somefolder/perfect.java",
        "com/test/subtest/random.java"
    };
    ImmutableSortedSet<Path> inputs = MorePaths.asPaths(ImmutableSortedSet.copyOf(args));

    // Build rule that owns all inputs
    BuildTarget target1 = BuildTargetFactory.newInstance("//base/name1:name1");
    BuildTarget target2 = BuildTargetFactory.newInstance("//base/name2:name2");
    BuildRule owner1Rule = new StubBuildRule(target1, inputs);
    BuildRule owner2Rule = new StubBuildRule(target2, inputs);

    // Create graph
    MutableDirectedGraph<BuildRule> mutableGraph = new MutableDirectedGraph<BuildRule>();
    mutableGraph.addNode(owner1Rule);
    mutableGraph.addNode(owner2Rule);

    DependencyGraph graph = new DependencyGraph(mutableGraph);

    // Create options
    AuditOwnerOptions options = getOptions(args);

    // Create command under test
    AuditOwnerCommand command = createAuditOwnerCommand(filesystem);

    // Generate report and verify nonFileInputs are filled in as expected.
    AuditOwnerCommand.OwnersReport report = command.generateOwnersReport(graph, options);
    assertTrue(report.nonFileInputs.isEmpty());
    assertTrue(report.nonExistentInputs.isEmpty());
    assertTrue(report.inputsWithNoOwners.isEmpty());

    assertTrue(report.owners.containsKey(owner1Rule));
    assertTrue(report.owners.containsKey(owner2Rule));
    assertEquals(owner1Rule.getInputs(), report.owners.get(owner1Rule));
    assertEquals(owner2Rule.getInputs(), report.owners.get(owner2Rule));
  }

}
