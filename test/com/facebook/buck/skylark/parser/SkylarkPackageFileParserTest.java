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

package com.facebook.buck.skylark.parser;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.eventhandler.Event;
import com.facebook.buck.core.starlark.eventhandler.EventCollector;
import com.facebook.buck.core.starlark.eventhandler.EventKind;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.skylark.function.SkylarkPackageModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class SkylarkPackageFileParserTest {

  private SkylarkPackageFileParser parser;
  private ProjectFilesystem projectFilesystem;
  private KnownRuleTypesProvider knownRuleTypesProvider;
  private EventCollector eventCollector;

  @Rule public ExpectedException thrown = ExpectedException.none();
  private Cells cell;

  @Before
  public void setUp() {
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    knownRuleTypesProvider = TestKnownRuleTypesProvider.create(pluginManager);

    eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    ProjectBuildFileParserOptions options =
        ProjectBuildFileParserOptions.builder()
            .setProjectRoot(cell.getRootCell().getRoot())
            .setAllowEmptyGlobs(false)
            .setIgnorePaths(ImmutableSet.of())
            .setBuildFileName("PACKAGE")
            .setRawConfig(
                ImmutableMap.of("dummy_section", ImmutableMap.of("dummy_key", "dummy_value")))
            .setDescriptions(ImmutableSet.of())
            .setPerFeatureProviders(ImmutableList.of())
            .setBuildFileImportWhitelist(ImmutableList.of())
            .setPythonInterpreter("skylark")
            .build();

    parser =
        SkylarkPackageFileParser.using(
            options,
            BuckEventBusForTests.newInstance(),
            BuckGlobals.of(
                SkylarkPackageModule.PACKAGE_MODULE,
                options.getDescriptions(),
                options.getUserDefinedRulesState(),
                options.getImplicitNativeRulesState(),
                new RuleFunctionFactory(new DefaultTypeCoercerFactory()),
                knownRuleTypesProvider.getUserDefinedRuleTypes(cell.getRootCell()),
                options.getPerFeatureProviders()),
            eventCollector);
  }

  @Test
  public void canParsePackage() throws Exception {
    AbsPath packageFile = projectFilesystem.resolve("src").resolve("PACKAGE");
    Files.createDirectories(packageFile.getParent().getPath());
    Files.write(packageFile.getPath(), Collections.singletonList("package(visibility=['//:foo'])"));

    PackageFileManifest packageFileManifest = parser.getManifest(packageFile);
    PackageMetadata pkg = packageFileManifest.getPackage();
    Assert.assertEquals("//:foo", pkg.getVisibility().get(0));
  }

  @Test
  public void missingPackageCreatesDefault() throws Exception {
    AbsPath packageFile = projectFilesystem.resolve("src").resolve("PACKAGE");
    Files.createDirectories(packageFile.getParent().getPath());
    Files.write(packageFile.getPath(), Collections.emptyList());

    PackageFileManifest packageFileManifest = parser.getManifest(packageFile);
    assertNotNull(packageFileManifest.getPackage());
  }

  @Test
  public void onlyOnePackageAllowed() throws Exception {
    AbsPath packageFile = projectFilesystem.resolve("src").resolve("PACKAGE");
    Files.createDirectories(packageFile.getParent().getPath());
    Files.write(packageFile.getPath(), Arrays.asList("package()", "package()"));

    thrown.expectMessage("Cannot evaluate file");
    parser.getManifest(packageFile);

    Event event = Iterables.getOnlyElement(eventCollector);
    assertThat(event.getKind(), is(EventKind.ERROR));
    assertThat(event.getMessage(), is("Only one package is allow per package file."));
  }

  @Test
  public void canUseBuiltInListFunctionInExtension() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath packageFile = directory.resolve("PACKAGE");
    AbsPath extensionFile = directory.resolve("helper_rules.bzl");
    Files.write(
        packageFile.getPath(),
        Arrays.asList("load('//src/test:helper_rules.bzl', 'custom_package')", "custom_package()"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList("def custom_package():", "  native.package(visibility=['PUBLIC'])"));
    parser.getManifest(packageFile).getPackage().getVisibility().iterator().next().equals("PUBLIC");
  }

  @Test
  public void parseIncludesIsReturned() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath packageFile = directory.resolve("PACKAGE");
    AbsPath extensionFile = directory.resolve("helper_rules.bzl");
    Files.write(
        packageFile.getPath(),
        Arrays.asList("load('//src/test:helper_rules.bzl', 'custom_package')", "custom_package()"));
    Files.write(
        extensionFile.getPath(), Arrays.asList("def custom_package():", "  native.package()"));
    ImmutableSortedSet<String> includes = parser.getIncludedFiles(packageFile);
    assertThat(includes, Matchers.hasSize(2));
    assertThat(
        includes.stream()
            .map(projectFilesystem::resolve)
            .map(
                p ->
                    p.getPath()
                        .getFileName()
                        .toString()) // simplify matching by stripping temporary path prefixes
            .collect(ImmutableList.toImmutableList()),
        Matchers.containsInAnyOrder("PACKAGE", "helper_rules.bzl"));
  }

  @Test
  public void cannotParseNonPackageRule() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    Files.createDirectories(buildFile.getParent().getPath());
    Files.write(
        buildFile.getPath(), Collections.singletonList("prebuilt_jar(" + "name='guava'," + ")"));

    thrown.expectMessage("Cannot parse");
    parser.getManifest(buildFile);

    Event event = Iterables.getOnlyElement(eventCollector);
    assertThat(event.getKind(), is(EventKind.ERROR));
    assertThat(event.getMessage(), is("name 'prebuilt_jar' is not defined"));
  }
}
