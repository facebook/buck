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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.keys.RuleKeyDiagnostics.Result;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.hasher.StringRuleKeyHasher;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExternalJavacTest extends EasyMockSupport {
  private static final Path PATH_TO_SRCS_LIST = Paths.get("srcs_list");
  public static final ImmutableSortedSet<Path> SOURCE_PATHS =
      ImmutableSortedSet.of(Paths.get("foobar.java"));

  @Rule public TemporaryPaths root = new TemporaryPaths();

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(root.getRoot());
  }

  @Test
  public void testJavacCommand() {
    Javac firstOrder = createTestStep();
    Javac warn = createTestStep();
    Javac transitive = createTestStep();

    assertEquals(
        filesystem.resolve("fakeJavac")
            + " -source 6 -target 6 -g -d . -classpath foo.jar @"
            + PATH_TO_SRCS_LIST,
        firstOrder.getDescription(
            getArgs().add("foo.jar").build(), SOURCE_PATHS, PATH_TO_SRCS_LIST));
    assertEquals(
        filesystem.resolve("fakeJavac")
            + " -source 6 -target 6 -g -d . -classpath foo.jar @"
            + PATH_TO_SRCS_LIST,
        warn.getDescription(getArgs().add("foo.jar").build(), SOURCE_PATHS, PATH_TO_SRCS_LIST));
    assertEquals(
        filesystem.resolve("fakeJavac")
            + " -source 6 -target 6 -g -d . -classpath bar.jar"
            + File.pathSeparator
            + "foo.jar @"
            + PATH_TO_SRCS_LIST,
        transitive.getDescription(
            getArgs().add("bar.jar" + File.pathSeparator + "foo.jar").build(),
            SOURCE_PATHS,
            PATH_TO_SRCS_LIST));
  }

  @Test
  public void externalJavacWillHashTheExternalIfNoVersionInformationIsReturned()
      throws IOException {
    // TODO(cjhopman): This test name implies we should be hashing the external file not just
    // adding its path.
    Path javac = root.newExecutableFile();

    Javac compiler = createExternalJavac(javac, "");
    Javac otherCompiler = createExternalJavac(javac, "");
    Path otherJavac = root.newExecutableFile();
    Javac otherPathCompiler = createExternalJavac(otherJavac, "");

    assertEquals(computeRuleKey(compiler), computeRuleKey(otherCompiler));
    assertNotEquals(computeRuleKey(compiler), computeRuleKey(otherPathCompiler));
  }

  @Test
  public void externalJavacWillUseTheToolFromABinaryBuildRule() throws IOException {
    // TODO(cjhopman): This test name implies we should be hashing the external file not just
    // adding its path.
    Path javac = root.newExecutableFile();
    ProcessExecutorParams javacExe =
        ProcessExecutorParams.builder()
            .addCommand(javac.toAbsolutePath().toString(), "-version")
            .build();
    FakeProcess javacProc = new FakeProcess(0, "", "");
    FakeProcessExecutor executor = new FakeProcessExecutor(ImmutableMap.of(javacExe, javacProc));

    TestActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget javacTarget = BuildTargetFactory.newInstance("//:javac");
    List<String> commandPrefix = new ArrayList<>();
    commandPrefix.add("command");
    commandPrefix.add("prefix");

    class SimpleBinaryRule extends AbstractBuildRule implements BinaryBuildRule {
      protected SimpleBinaryRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
        super(buildTarget, projectFilesystem);
      }

      @Override
      public Tool getExecutableCommand(OutputLabel outputLabel) {
        return new Tool() {
          @AddToRuleKey
          private final ImmutableList<String> flags = ImmutableList.copyOf(commandPrefix);

          @Override
          public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
            return flags;
          }

          @Override
          public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
            return ImmutableMap.of();
          }
        };
      }

      @Override
      public SortedSet<BuildRule> getBuildDeps() {
        return ImmutableSortedSet.of();
      }

      @Override
      public ImmutableList<? extends Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext) {
        return ImmutableList.of();
      }

      @Nullable
      @Override
      public SourcePath getSourcePathToOutput() {
        return ExplicitBuildTargetSourcePath.of(
            javacTarget,
            BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/javac"));
      }
    }
    BinaryBuildRule binaryRule = new SimpleBinaryRule(javacTarget, filesystem);
    graphBuilder.computeIfAbsent(javacTarget, ignored -> binaryRule);

    Javac compiler =
        new ExternalJavacProvider(executor, DefaultBuildTargetSourcePath.of(javacTarget))
            .resolve(graphBuilder);

    String key = computeRuleKey(compiler);

    commandPrefix.add("someflag");

    compiler =
        new ExternalJavacProvider(executor, DefaultBuildTargetSourcePath.of(javacTarget))
            .resolve(graphBuilder);
    String otherKey = computeRuleKey(compiler);

    assertNotEquals(key, otherKey);

    // Just confirm that keys are stable so that we the not equals above is actually due to the
    // command change.

    compiler =
        new ExternalJavacProvider(executor, DefaultBuildTargetSourcePath.of(javacTarget))
            .resolve(graphBuilder);

    assertEquals(otherKey, computeRuleKey(compiler));
  }

  @Test
  public void externalJavacWillHashTheJavacVersionIfPresent() throws IOException {
    Path javac = root.newExecutableFile();

    Javac compiler = createExternalJavac(javac, "mozzarella");
    Javac otherCompiler = createExternalJavac(javac, "brie");
    Path otherJavac = root.newExecutableFile();
    Javac otherPathCompiler = createExternalJavac(otherJavac, "mozzarella");

    assertEquals(computeRuleKey(compiler), computeRuleKey(otherPathCompiler));
    assertNotEquals(computeRuleKey(compiler), computeRuleKey(otherCompiler));
  }

  public String computeRuleKey(Javac compiler) {
    Result<RuleKey, String> result =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), new TestActionGraphBuilder())
            .buildForDiagnostics(compiler, new StringRuleKeyHasher());
    return result.diagKey;
  }

  public Javac createExternalJavac(Path javac, String reportedJavacVersion) {
    ProcessExecutorParams javacExe =
        ProcessExecutorParams.builder()
            .addCommand(javac.toAbsolutePath().toString(), "-version")
            .build();
    FakeProcess javacProc = new FakeProcess(0, "", reportedJavacVersion);
    FakeProcessExecutor executor = new FakeProcessExecutor(ImmutableMap.of(javacExe, javacProc));

    return new ExternalJavacProvider(executor, FakeSourcePath.of(javac))
        .resolve(new TestActionGraphBuilder());
  }

  private ImmutableList.Builder<String> getArgs() {
    return ImmutableList.<String>builder()
        .add("-source", "6", "-target", "6", "-g", "-d", ".", "-classpath");
  }

  private Javac createTestStep() {
    Path fakeJavac = Paths.get("fakeJavac");
    return new ExternalJavacProvider(
            new DefaultProcessExecutor(Console.createNullConsole()),
            FakeSourcePath.of(filesystem, fakeJavac))
        .resolve(new TestActionGraphBuilder());
  }
}
