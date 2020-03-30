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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.features.python.toolchain.impl.PythonPlatformsProviderFactoryUtils;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.hamcrest.comparator.ComparatorMatcherBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PrebuiltPythonLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt_package", tmp);
    workspace.setUp();

    // EGGs are versioned to the version of Python they were built it, but the EGG for this test
    // doesn't actually matter.
    String version =
        PythonPlatformsProviderFactoryUtils.getPythonEnvironment(
                FakeBuckConfig.builder().build(),
                new DefaultProcessExecutor(new TestConsole(Verbosity.SILENT)),
                new ExecutableFinder())
            .getPythonVersion()
            .getVersionString();
    if (!version.startsWith("2.6")) {
      workspace.move(
          "dist/package-0.1-py2.6.egg", "dist/package-0.1-py" + version.substring(0, 3) + ".egg");
    }
  }

  @Test
  public void testRunPexWithEggDependency() {
    ProcessResult eggResults = workspace.runBuckCommand("run", "//:main_egg");
    eggResults.assertSuccess();

    ProcessResult whlResults = workspace.runBuckCommand("run", "//:main_whl");
    whlResults.assertSuccess();
  }

  @Test
  public void buildingAPrebuiltPythonLibraryExtractsIt() throws IOException {
    ProcessResult eggResults = workspace.runBuckCommand("run", "//:main_egg");
    eggResults.assertSuccess();

    ProcessResult whlResults = workspace.runBuckCommand("run", "//:main_whl");
    whlResults.assertSuccess();

    Path extractedEgg =
        workspace.getGenPath(BuildTargetFactory.newInstance("//:python_egg"), "__%s__extracted");
    Path extractedWhl =
        workspace.getGenPath(BuildTargetFactory.newInstance("//:python_whl"), "__%s__extracted");

    Assert.assertTrue(Files.exists(extractedEgg.resolve(Paths.get("package", "__init__.py"))));
    Assert.assertTrue(Files.exists(extractedEgg.resolve(Paths.get("package", "file.py"))));
    Assert.assertTrue(Files.exists(extractedEgg.resolve(Paths.get("EGG-INFO", "PKG-INFO"))));
    Assert.assertTrue(Files.exists(extractedEgg.resolve(Paths.get("EGG-INFO", "SOURCES.txt"))));
    Assert.assertTrue(
        Files.exists(extractedEgg.resolve(Paths.get("EGG-INFO", "dependency_links.txt"))));
    Assert.assertTrue(Files.exists(extractedEgg.resolve(Paths.get("EGG-INFO", "top_level.txt"))));
    Assert.assertTrue(Files.exists(extractedEgg.resolve(Paths.get("EGG-INFO", "zip-safe"))));

    Assert.assertTrue(Files.exists(extractedWhl.resolve(Paths.get("package", "__init__.py"))));
    Assert.assertTrue(Files.exists(extractedWhl.resolve(Paths.get("package", "file.py"))));
    Assert.assertTrue(
        Files.exists(extractedWhl.resolve(Paths.get("package-0.1.dist-info", "DESCRIPTION.rst"))));
    Assert.assertTrue(
        Files.exists(extractedWhl.resolve(Paths.get("package-0.1.dist-info", "metadata.json"))));
    Assert.assertTrue(
        Files.exists(extractedWhl.resolve(Paths.get("package-0.1.dist-info", "top_level.txt"))));
    Assert.assertTrue(
        Files.exists(extractedWhl.resolve(Paths.get("package-0.1.dist-info", "WHEEL"))));
    Assert.assertTrue(
        Files.exists(extractedWhl.resolve(Paths.get("package-0.1.dist-info", "METADATA"))));
    Assert.assertTrue(
        Files.exists(extractedWhl.resolve(Paths.get("package-0.1.dist-info", "RECORD"))));
  }

  @Test
  public void compile() throws IOException, InterruptedException {
    Path py3 = PythonTestUtils.assumeInterpreter("python3");
    PythonTestUtils.assumeVersion(
        py3,
        Matchers.any(String.class),
        ComparatorMatcherBuilder.comparedBy(new VersionStringComparator())
            .greaterThanOrEqualTo("3.7"));
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    RelPath dir =
        filesystem.relativize(
            workspace.buildAndReturnOutput(
                "-c", "python.interpreter=" + py3, "//:python_egg#py-default,default,compile"));
    assertThat(
        filesystem.asView().getFilesUnderPath(dir.getPath(), EnumSet.noneOf(FileVisitOption.class))
            .stream()
            .map(p -> PathFormatter.pathWithUnixSeparators(dir.getPath().relativize(p)))
            .collect(ImmutableList.toImmutableList()),
        Matchers.containsInAnyOrder(
            Matchers.matchesRegex("package(/__pycache__)?/file(.cpython-3[0-9])?.pyc"),
            Matchers.matchesRegex("package(/__pycache__)?/__init__(.cpython-3[0-9])?.pyc")));
  }

  @Test
  public void compileOptOut() throws IOException {
    Path py3 = PythonTestUtils.assumeInterpreter("python3");
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    RelPath binPath =
        filesystem.relativize(
            workspace.buildAndReturnOutput(
                "-c",
                "python.interpreter=" + py3,
                "-c",
                "python.pex_flags=--directory",
                "-c",
                "python.package_style=" + PythonBuckConfig.PackageStyle.STANDALONE,
                "//:main_whl_compile_opt_out"));
    assertThat(
        workspace.getProjectFileSystem().asView()
            .getFilesUnderPath(binPath.getPath(), EnumSet.noneOf(FileVisitOption.class)).stream()
            .map(p -> binPath.getPath().relativize(p))
            .map(Path::toString)
            .collect(Collectors.toList()),
        Matchers.everyItem(
            Matchers.not(
                Matchers.anyOf(
                    Matchers.matchesRegex("package(/__pycache__)?/file(.cpython-3[0-9])?.pyc"),
                    Matchers.matchesRegex(
                        "package(/__pycache__)?/__init__(.cpython-3[0-9])?.pyc")))));
  }
}
