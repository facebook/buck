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

package com.facebook.buck.jvm.java;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.step.Step;
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
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.easymock.Capture;
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
    ProcessExecutorParams javacExe =
        ProcessExecutorParams.builder()
            .addCommand(javac.toAbsolutePath().toString(), "-version")
            .build();
    FakeProcess javacProc = new FakeProcess(0, "", "");
    FakeProcessExecutor executor = new FakeProcessExecutor(ImmutableMap.of(javacExe, javacProc));
    Javac compiler =
        new ExternalJavacProvider(executor, FakeSourcePath.of(javac))
            .resolve(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    RuleKeyObjectSink sink = createMock(RuleKeyObjectSink.class);
    Capture<Supplier<Tool>> identifier = new Capture<>();
    expect(sink.setReflectively(eq(".class"), anyObject())).andReturn(sink);
    expect(sink.setReflectively(eq("javac"), capture(identifier))).andReturn(sink);
    replay(sink);
    AlterRuleKeys.amendKey(sink, compiler);
    verify(sink);
    Tool tool = identifier.getValue().get();

    assertTrue(tool instanceof VersionedTool);
    assertEquals(javac.toString(), ((VersionedTool) tool).getVersion());
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
    ImmutableList<String> commandPrefix = ImmutableList.of("command", "prefix");
    class SimpleBinaryRule extends AbstractBuildRule implements BinaryBuildRule {
      protected SimpleBinaryRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
        super(buildTarget, projectFilesystem);
      }

      @Override
      public Tool getExecutableCommand() {
        return new Tool() {
          @Override
          public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
            return commandPrefix;
          }

          @Override
          public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
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

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    Javac compiler =
        new ExternalJavacProvider(executor, DefaultBuildTargetSourcePath.of(javacTarget))
            .resolve(ruleFinder);
    RuleKeyObjectSink sink = createMock(RuleKeyObjectSink.class);
    Capture<Supplier<Tool>> identifier = new Capture<>();
    expect(sink.setReflectively(eq(".class"), anyObject())).andReturn(sink);
    expect(sink.setReflectively(eq("javac"), capture(identifier))).andReturn(sink);
    replay(sink);
    AlterRuleKeys.amendKey(sink, compiler);
    verify(sink);
    Tool tool = identifier.getValue().get();

    assertEquals(commandPrefix, tool.getCommandPrefix(DefaultSourcePathResolver.from(ruleFinder)));
  }

  @Test
  public void externalJavacWillHashTheJavacVersionIfPresent() throws IOException {
    Path javac = root.newExecutableFile();

    String reportedJavacVersion = "mozzarella";

    JavacVersion javacVersion = JavacVersion.of(reportedJavacVersion);
    ProcessExecutorParams javacExe =
        ProcessExecutorParams.builder()
            .addCommand(javac.toAbsolutePath().toString(), "-version")
            .build();
    FakeProcess javacProc = new FakeProcess(0, "", reportedJavacVersion);
    FakeProcessExecutor executor = new FakeProcessExecutor(ImmutableMap.of(javacExe, javacProc));

    Javac compiler =
        new ExternalJavacProvider(executor, FakeSourcePath.of(javac))
            .resolve(new SourcePathRuleFinder(new TestActionGraphBuilder()));

    RuleKeyObjectSink sink = createMock(RuleKeyObjectSink.class);
    Capture<Supplier<Tool>> identifier = new Capture<>();
    expect(sink.setReflectively(eq(".class"), anyObject())).andReturn(sink);
    expect(sink.setReflectively(eq("javac"), capture(identifier))).andReturn(sink);
    replay(sink);
    AlterRuleKeys.amendKey(sink, compiler);
    verify(sink);
    Tool tool = identifier.getValue().get();

    assertTrue(tool instanceof VersionedTool);
    assertEquals(javacVersion.toString(), ((VersionedTool) tool).getVersion());
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
        .resolve(new SourcePathRuleFinder(new TestActionGraphBuilder()));
  }
}
