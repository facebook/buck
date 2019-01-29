/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxInferEnhancer;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class MultiarchFileTest {
  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {
            "AppleBinaryDescription",
            (Supplier<DescriptionWithTargetGraph<?>>)
                () -> FakeAppleRuleDescriptions.BINARY_DESCRIPTION,
            (NodeBuilderFactory) AppleBinaryBuilder::createBuilder
          },
          {
            "AppleLibraryDescription (static)",
            (Supplier<DescriptionWithTargetGraph<?>>)
                () -> FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION,
            (NodeBuilderFactory)
                target ->
                    AppleLibraryBuilder.createBuilder(
                            target.withAppendedFlavors(InternalFlavor.of("static")))
                        .setSrcs(
                            ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))))
          },
          {
            "AppleLibraryDescription (shared)",
            (Supplier<DescriptionWithTargetGraph<?>>)
                () -> FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION,
            (NodeBuilderFactory)
                target ->
                    AppleLibraryBuilder.createBuilder(
                            target.withAppendedFlavors(InternalFlavor.of("shared")))
                        .setSrcs(
                            ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))))
          },
        });
  }

  @Parameterized.Parameter(0)
  public String name;

  @Parameterized.Parameter(1)
  public Supplier<DescriptionWithTargetGraph<?>> descriptionFactory;

  @Parameterized.Parameter(2)
  public NodeBuilderFactory nodeBuilderFactory;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void shouldAllowMultiplePlatformFlavors() {
    assertTrue(
        ((Flavored) descriptionFactory.get())
            .hasFlavors(
                ImmutableSet.of(
                    InternalFlavor.of("iphoneos-i386"), InternalFlavor.of("iphoneos-x86_64"))));
  }

  @SuppressWarnings({"unchecked"})
  @Test
  public void descriptionWithMultiplePlatformArgsShouldGenerateMultiarchFile() {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:thing#iphoneos-i386,iphoneos-x86_64");
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(new AppleLibraryBuilder(target).build()));
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRule multiarchRule =
        nodeBuilderFactory.getNodeBuilder(target).build(graphBuilder, filesystem);

    assertThat(multiarchRule, instanceOf(MultiarchFile.class));

    ImmutableList<? extends Step> steps =
        multiarchRule.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());

    ShellStep step = Iterables.getLast(Iterables.filter(steps, ShellStep.class));

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    ImmutableList<String> command = step.getShellCommand(executionContext);
    assertThat(
        command,
        Matchers.contains(
            endsWith("lipo"),
            equalTo("-create"),
            equalTo("-output"),
            containsString("foo/thing#"),
            containsString("/thing#"),
            containsString("/thing#")));
  }

  @Test
  public void descriptionWithMultipleDifferentSdksShouldFail() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    HumanReadableException exception = null;
    try {
      nodeBuilderFactory
          .getNodeBuilder(
              BuildTargetFactory.newInstance("//foo:xctest#iphoneos-i386,macosx-x86_64"))
          .build(graphBuilder);
    } catch (HumanReadableException e) {
      exception = e;
    }
    assertThat(exception, notNullValue());
    assertThat(
        "Should throw exception about different architectures",
        exception.getHumanReadableErrorMessage(),
        endsWith("Fat binaries can only be generated from binaries compiled for the same SDK."));
  }

  @Test
  public void ruleWithSpecialBuildActionShouldFail() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    HumanReadableException exception = null;
    Iterable<Flavor> forbiddenFlavors =
        ImmutableList.<Flavor>builder()
            .addAll(CxxInferEnhancer.INFER_FLAVOR_DOMAIN.getFlavors())
            .add(CxxCompilationDatabase.COMPILATION_DATABASE)
            .build();

    for (Flavor flavor : forbiddenFlavors) {
      try {
        nodeBuilderFactory
            .getNodeBuilder(
                BuildTargetFactory.newInstance(
                    "//foo:xctest#" + "iphoneos-i386,iphoneos-x86_64," + flavor))
            .build(graphBuilder);
      } catch (HumanReadableException e) {
        exception = e;
      }
      assertThat(exception, notNullValue());
      assertThat(
          "Should throw exception about special build actions.",
          exception.getHumanReadableErrorMessage(),
          endsWith("Fat binaries is only supported when building an actual binary."));
    }
  }

  @Test
  public void propagatesSingleArchRulesAndTheirDsymDepsAsDsymDeps() {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:thing#iphoneos-i386,iphoneos-x86_64");
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(new AppleLibraryBuilder(target).build()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MultiarchFile multiarchRule =
        (MultiarchFile) nodeBuilderFactory.getNodeBuilder(target).build(graphBuilder, filesystem);
    ImmutableList<BuildRule> dsymDeps =
        multiarchRule.getAppleDebugSymbolDeps().collect(ImmutableList.toImmutableList());
    assertThat(
        "dsym deps should contain single arch rules themselves",
        dsymDeps,
        hasItems(multiarchRule.getBuildDeps().toArray(new BuildRule[0])));
    assertThat(
        "dsym deps should contain dsym deps of single arch rules",
        dsymDeps,
        hasItems(
            RichStream.from(multiarchRule.getBuildDeps())
                .filter(HasAppleDebugSymbolDeps.class)
                .flatMap(HasAppleDebugSymbolDeps::getAppleDebugSymbolDeps)
                .toArray(BuildRule[]::new)));
  }

  /** Rule builders pass BuildTarget as a constructor arg, so this is unfortunately necessary. */
  private interface NodeBuilderFactory {
    AbstractNodeBuilder<?, ?, ?, ?> getNodeBuilder(BuildTarget target);
  }
}
