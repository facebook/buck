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

package com.facebook.buck.features.go;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.AllOf.allOf;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.features.go.GoListStep.ListType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GoDescriptorsTest {

  @Rule public TemporaryPaths tmpPath = new TemporaryPaths();

  private ImmutableMap<String, String> getPackageImportMap(
      Iterable<String> globalVendorPath, String basePackage, Iterable<String> packages) {
    return ImmutableMap.copyOf(
        FluentIterable.from(
                GoDescriptors.getPackageImportMap(
                        ImmutableList.copyOf(
                            FluentIterable.from(globalVendorPath).transform(Paths::get)),
                        ForwardRelativePath.of(basePackage),
                        FluentIterable.from(packages).transform(Paths::get))
                    .entrySet())
            .transform(
                input ->
                    Maps.immutableEntry(
                        PathFormatter.pathWithUnixSeparators(input.getKey()),
                        PathFormatter.pathWithUnixSeparators(input.getValue()))));
  }

  @Test
  public void testImportMapEmpty() {
    assertThat(
        getPackageImportMap(
            ImmutableList.of(""),
            "foo/bar/baz",
            ImmutableList.of("foo/bar", "bar", "foo/bar/baz/waffle")),
        Matchers.anEmptyMap());
  }

  @Test
  public void testImportMapRoot() {
    assertThat(
        getPackageImportMap(
            ImmutableList.of(""),
            "foo/bar/baz",
            ImmutableList.of("foo/bar", "bar", "foo/bar/baz/waffle", "foo/vendor/hello/world")),
        Matchers.equalTo(ImmutableMap.of("hello/world", "foo/vendor/hello/world")));
  }

  @Test
  public void testImportMapNonRoot() {
    assertThat(
        getPackageImportMap(
            ImmutableList.of(""),
            "foo/bar/baz",
            ImmutableList.of("foo/bar", "bar", "foo/bar/baz/waffle", "foo/bar/vendor/hello/world")),
        Matchers.equalTo(ImmutableMap.of("hello/world", "foo/bar/vendor/hello/world")));
  }

  @Test
  public void testImportMapLongestWins() {
    assertThat(
        getPackageImportMap(
            ImmutableList.of("qux"),
            "foo/bar/baz",
            ImmutableList.of(
                "foo/bar",
                "bar",
                "foo/bar/baz/waffle",
                "qux/hello/world",
                "vendor/hello/world",
                "foo/bar/vendor/hello/world")),
        Matchers.equalTo(ImmutableMap.of("hello/world", "foo/bar/vendor/hello/world")));
  }

  @Test
  public void testImportMapGlobal() {
    assertThat(
        getPackageImportMap(
            ImmutableList.of("qux"),
            "foo/bar/baz",
            ImmutableList.of("foo/bar", "bar", "qux/hello/world")),
        Matchers.equalTo(ImmutableMap.of("hello/world", "qux/hello/world")));
  }

  @Test
  public void testBuildRuleAsSrcAddsRuleToDependencies() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    GoPlatform goPlatform =
        GoTestUtils.DEFAULT_PLATFORM.withGoArch(GoArch.AMD64).withGoOs(GoOs.LINUX);
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpPath.getRoot());
    BuildTarget target =
        BuildTargetFactory.newInstance("//:go_library").withFlavors(goPlatform.getFlavor());
    BuildTarget srcTarget = BuildTargetFactory.newInstance("//:go_genrule");

    GenruleBuilder.newGenruleBuilder(srcTarget)
        .setOut("out.go")
        .setCmd("echo 'test' > $OUT")
        .build(graphBuilder);

    BuildRuleParams params = TestBuildRuleParams.create();
    GoBuckConfig goBuckConfig = new GoBuckConfig(FakeBuckConfig.builder().build());

    GoCompile compile =
        GoDescriptors.createGoCompileRule(
            target,
            filesystem,
            params,
            graphBuilder,
            goBuckConfig,
            Paths.get("package"),
            ImmutableSet.of(
                PathSourcePath.of(filesystem, Paths.get("not_build_target.go")),
                DefaultBuildTargetSourcePath.of(srcTarget)),
            ImmutableList.of(),
            ImmutableList.of(),
            goPlatform,
            ImmutableList.of(),
            ImmutableList.of(),
            Collections.singletonList(ListType.GoFiles));

    Assert.assertTrue(
        compile.getBuildDeps().stream()
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableList.toImmutableList())
            .contains(srcTarget));
  }

  @Test
  public void testBuildRuleAsSrcAddsRuleToDependenciesOfBinary() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    GoPlatform goPlatform = GoTestUtils.DEFAULT_PLATFORM;
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpPath.getRoot());
    BuildTarget target =
        BuildTargetFactory.newInstance("//:go_library").withFlavors(goPlatform.getFlavor());
    BuildTarget srcTarget = BuildTargetFactory.newInstance("//:go_genrule");

    GenruleBuilder.newGenruleBuilder(srcTarget)
        .setOut("out.go")
        .setCmd("echo 'test' > $OUT")
        .build(graphBuilder);

    BuildRuleParams params = TestBuildRuleParams.create();
    GoBuckConfig goBuckConfig = new GoBuckConfig(FakeBuckConfig.builder().build());

    GoBinary binary =
        GoDescriptors.createGoBinaryRule(
            target,
            filesystem,
            params,
            graphBuilder,
            goBuckConfig,
            Linker.LinkableDepType.STATIC_PIC,
            Optional.empty(),
            ImmutableSet.of(
                PathSourcePath.of(filesystem, Paths.get("not_build_target.go")),
                DefaultBuildTargetSourcePath.of(srcTarget)),
            ImmutableSortedSet.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            goPlatform);

    System.out.println(binary.getBuildDeps());
    GoCompile compile =
        binary.getBuildDeps().stream()
            .filter(
                dep ->
                    dep.getBuildTarget()
                        .getFullyQualifiedName()
                        .equals("//:go_library#compile," + goPlatform.getFlavor()))
            .map(dep -> (GoCompile) dep)
            .findFirst()
            .get();

    Assert.assertTrue(
        compile.getBuildDeps().stream()
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableList.toImmutableList())
            .contains(srcTarget));
  }

  @Test
  public void testBuildRuleWithLinkModeArg() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();

    GoPlatform goPlatform = GoTestUtils.CUSTOM_PLATFORM_WITH_LINKER_ARGS;
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpPath.getRoot());

    BuildRuleParams params = TestBuildRuleParams.create();
    GoBuckConfig goBuckConfig = new GoBuckConfig(FakeBuckConfig.builder().build());

    ImmutableMap<Optional<GoLinkStep.LinkMode>, Matcher> modes =
        ImmutableMap.of(
            Optional.empty(),
            allOf(hasItems("-linkmode", "internal"), not(hasItems("-extld"))),
            Optional.of(GoLinkStep.LinkMode.INTERNAL),
            allOf(hasItems("-linkmode", "internal"), not(hasItems("-extld"))),
            Optional.of(GoLinkStep.LinkMode.EXTERNAL),
            hasItems("-linkmode", "external", "-extld"));

    int i = 0;
    for (Map.Entry<Optional<GoLinkStep.LinkMode>, Matcher> entry : modes.entrySet()) {
      i++;
      BuildTarget target =
          BuildTargetFactory.newInstance("//:go_library_" + i).withFlavors(goPlatform.getFlavor());
      GoBinary binary =
          GoDescriptors.createGoBinaryRule(
              target,
              filesystem,
              params,
              graphBuilder,
              goBuckConfig,
              Linker.LinkableDepType.STATIC_PIC,
              entry.getKey(),
              ImmutableSet.of(),
              ImmutableSortedSet.of(),
              ImmutableList.of(),
              ImmutableList.of(),
              ImmutableList.of(),
              ImmutableList.of(),
              goPlatform);

      ImmutableList<Step> buildSteps =
          binary.getBuildSteps(
              FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());
      GoLinkStep linkStep =
          buildSteps.stream()
              .filter(GoLinkStep.class::isInstance)
              .map(GoLinkStep.class::cast)
              .collect(ImmutableList.toImmutableList())
              .get(0);

      ImmutableList<String> shellCommand =
          linkStep.getShellCommandInternal(TestExecutionContext.newInstance());

      assertThat(shellCommand, entry.getValue());
    }
  }
}
