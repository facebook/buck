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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxInferEnhancer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class MultiarchFileTest {
  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
        {
            "AppleBinaryDescription",
            FakeAppleRuleDescriptions.BINARY_DESCRIPTION,
            new NodeBuilderFactory() {
              @Override
              public AbstractNodeBuilder<?> getNodeBuilder(BuildTarget target) {
                return AppleBinaryBuilder.createBuilder(target);
              }
            }
        },
        {
            "AppleLibraryDescription (static)",
            FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION,
            new NodeBuilderFactory() {
              @Override
              public AbstractNodeBuilder<?> getNodeBuilder(BuildTarget target) {
                return AppleLibraryBuilder
                    .createBuilder(target.withAppendedFlavors(ImmutableFlavor.of("static")))
                    .setSrcs(Optional.of(ImmutableSortedSet.of(
                        SourceWithFlags.of(new FakeSourcePath("foo.c")))));
              }
            }
        },
        {
            "AppleLibraryDescription (shared)",
            FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION,
            new NodeBuilderFactory() {
              @Override
              public AbstractNodeBuilder<?> getNodeBuilder(BuildTarget target) {
                return AppleLibraryBuilder
                    .createBuilder(target.withAppendedFlavors(ImmutableFlavor.of("shared")))
                    .setSrcs(Optional.of(ImmutableSortedSet.of(
                        SourceWithFlags.of(new FakeSourcePath("foo.c")))));
              }
            }
        },
    });
  }

  @Parameterized.Parameter(0)
  public String name;

  @Parameterized.Parameter(1)
  public Description<?> description;

  @Parameterized.Parameter(2)
  public NodeBuilderFactory nodeBuilderFactory;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void shouldAllowMultiplePlatformFlavors() {
    assertTrue(
        ((Flavored) description).hasFlavors(
            ImmutableSet.of(
                ImmutableFlavor.of("iphoneos-i386"),
                ImmutableFlavor.of("iphoneos-x86_64"))));
  }

  @SuppressWarnings({"unchecked"})
  @Test
  public void descriptionWithMultiplePlatformArgsShouldGenerateMultiarchFile()
      throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRule multiarchRule = nodeBuilderFactory
        .getNodeBuilder(BuildTargetFactory.newInstance("//foo:thing#iphoneos-i386,iphoneos-x86_64"))
        .build(resolver, filesystem);

    assertThat(multiarchRule, instanceOf(MultiarchFile.class));

    ImmutableList<Step> steps = multiarchRule.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());

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
  public void descriptionWithMultipleDifferentSdksShouldFail() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    HumanReadableException exception = null;
    try {
      nodeBuilderFactory
          .getNodeBuilder(
              BuildTargetFactory.newInstance("//foo:xctest#iphoneos-i386,macosx-x86_64"))
          .build(resolver);
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
  public void ruleWithSpecialBuildActionShouldFail() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    HumanReadableException exception = null;
    Iterable<Flavor> forbiddenFlavors = ImmutableList.<Flavor>builder()
        .addAll(CxxInferEnhancer.InferFlavors.getAll())
        .add(CxxCompilationDatabase.COMPILATION_DATABASE)
        .build();

    for (Flavor flavor : forbiddenFlavors) {
      try {
        nodeBuilderFactory
            .getNodeBuilder(
                BuildTargetFactory.newInstance("//foo:xctest#" +
                    "iphoneos-i386,iphoneos-x86_64," + flavor.toString()))
            .build(resolver);
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

  /**
   * Rule builders pass BuildTarget as a constructor arg, so this is unfortunately necessary.
   */
  private interface NodeBuilderFactory {
    AbstractNodeBuilder<?> getNodeBuilder(BuildTarget target);
  }
}
