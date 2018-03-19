/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.go;

import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.go.GoListStep.FileType;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.nio.file.Paths;
import java.util.Arrays;
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
                        Paths.get(basePackage),
                        FluentIterable.from(packages).transform(Paths::get))
                    .entrySet())
            .transform(
                input ->
                    Maps.immutableEntry(
                        MorePaths.pathWithUnixSeparators(input.getKey()),
                        MorePaths.pathWithUnixSeparators(input.getValue()))));
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
    BuildRuleResolver resolver = new TestBuildRuleResolver();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpPath.getRoot());
    BuildTarget target =
        BuildTargetFactory.newInstance("//:go_library")
            .withFlavors(InternalFlavor.of("linux_amd64"));
    BuildTarget srcTarget = BuildTargetFactory.newInstance("//:go_genrule");

    GenruleBuilder.newGenruleBuilder(srcTarget)
        .setOut("out.go")
        .setCmd("echo 'test' > $OUT")
        .build(resolver);

    BuildRuleParams params = TestBuildRuleParams.create();
    GoBuckConfig goBuckConfig = new GoBuckConfig(FakeBuckConfig.builder().build());
    GoPlatform goPlatform = GoPlatform.builder().setGoArch("amd64").setGoOs("linux").build();
    GoToolchain goToolchain =
        GoToolchain.of(
            goBuckConfig,
            new GoPlatformFlavorDomain(),
            goPlatform,
            tmpPath.getRoot(),
            tmpPath.getRoot().resolve("go"));

    GoCompile compile =
        GoDescriptors.createGoCompileRule(
            target,
            filesystem,
            params,
            resolver,
            goBuckConfig,
            goToolchain,
            Paths.get("package"),
            ImmutableSet.of(
                PathSourcePath.of(filesystem, Paths.get("not_build_target.go")),
                DefaultBuildTargetSourcePath.of(srcTarget)),
            ImmutableList.of(),
            ImmutableList.of(),
            goPlatform,
            ImmutableList.of(),
            ImmutableSortedSet.of(),
            Arrays.asList(FileType.GoFiles));

    Assert.assertTrue(
        compile
            .getBuildDeps()
            .stream()
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableList.toImmutableList())
            .contains(srcTarget));
  }

  @Test
  public void testBuildRuleAsSrcAddsRuleToDependenciesOfBinary() {
    BuildRuleResolver resolver = new TestBuildRuleResolver();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpPath.getRoot());
    BuildTarget target =
        BuildTargetFactory.newInstance("//:go_library")
            .withFlavors(InternalFlavor.of("linux_amd64"));
    BuildTarget srcTarget = BuildTargetFactory.newInstance("//:go_genrule");

    GenruleBuilder.newGenruleBuilder(srcTarget)
        .setOut("out.go")
        .setCmd("echo 'test' > $OUT")
        .build(resolver);

    BuildRuleParams params = TestBuildRuleParams.create();
    GoBuckConfig goBuckConfig = new GoBuckConfig(FakeBuckConfig.builder().build());
    GoPlatform goPlatform = GoPlatform.builder().setGoArch("amd64").setGoOs("linux").build();
    GoToolchain goToolchain =
        GoToolchain.of(
            goBuckConfig,
            new GoPlatformFlavorDomain(),
            goPlatform,
            tmpPath.getRoot(),
            tmpPath.getRoot().resolve("go"));

    GoBinary binary =
        GoDescriptors.createGoBinaryRule(
            target,
            filesystem,
            params,
            resolver,
            goBuckConfig,
            goToolchain,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableSet.of(
                PathSourcePath.of(filesystem, Paths.get("not_build_target.go")),
                DefaultBuildTargetSourcePath.of(srcTarget)),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            goPlatform,
            ImmutableSortedSet.of());

    GoCompile compile =
        binary
            .getBuildDeps()
            .stream()
            .filter(
                dep ->
                    dep.getBuildTarget()
                        .getFullyQualifiedName()
                        .equals("//:go_library#compile,linux_amd64"))
            .map(dep -> (GoCompile) dep)
            .findFirst()
            .get();

    Assert.assertTrue(
        compile
            .getBuildDeps()
            .stream()
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableList.toImmutableList())
            .contains(srcTarget));
  }
}
