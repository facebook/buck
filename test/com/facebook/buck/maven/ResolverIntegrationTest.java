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

package com.facebook.buck.maven;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.impl.PythonInterpreterFromConfig;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.jvm.java.PrebuiltJarDescription;
import com.facebook.buck.maven.aether.Repository;
import com.facebook.buck.parser.PythonDslProjectBuildFileParser;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class ResolverIntegrationTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  private static HttpdForTests httpd;
  private PythonDslProjectBuildFileParser buildFileParser;
  private static Path repo;
  private AbsPath buckRepoRoot;
  private AbsPath thirdParty;
  private RelPath thirdPartyRelative;
  private AbsPath localRepo;

  @BeforeClass
  public static void setUpFakeMavenRepo() throws Exception {
    repo = TestDataHelper.getTestDataDirectory(ResolverIntegrationTest.class);
    httpd = new HttpdForTests();
    httpd.addHandler(new HttpdForTests.FileDispenserRequestHandler(repo));
    httpd.start();
  }

  @AfterClass
  public static void shutDownHttpd() throws Exception {
    httpd.close();
  }

  @After
  public void closeParser() throws BuildFileParseException, InterruptedException, IOException {
    buildFileParser.close();
  }

  @Before
  public void setUpRepos() throws Exception {
    buckRepoRoot = temp.newFolder();
    thirdPartyRelative = RelPath.of(Paths.get("third-party").resolve("java"));
    thirdParty = buckRepoRoot.resolve(thirdPartyRelative);
    localRepo = temp.newFolder();

    ProjectFilesystem filesystem =
        new FakeProjectFilesystem(CanonicalCellName.rootCell(), buckRepoRoot);
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(buckConfig);

    ImmutableSet<DescriptionWithTargetGraph<?>> descriptions =
        ImmutableSet.of(new RemoteFileDescription(), new PrebuiltJarDescription());

    buildFileParser =
        new PythonDslProjectBuildFileParser(
            ProjectBuildFileParserOptions.builder()
                .setProjectRoot(filesystem.getRootPath())
                .setPythonInterpreter(
                    new PythonInterpreterFromConfig(pythonBuckConfig, new ExecutableFinder())
                        .getPythonInterpreterPath()
                        .toString())
                .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
                .setIgnorePaths(filesystem.getIgnorePaths())
                .setBuildFileName(parserConfig.getBuildFileName())
                .setDefaultIncludes(parserConfig.getDefaultIncludes())
                .setDescriptions(descriptions)
                .setBuildFileImportWhitelist(parserConfig.getBuildFileImportWhitelist())
                .build(),
            new DefaultTypeCoercerFactory(),
            ImmutableMap.of(),
            BuckEventBusForTests.newInstance(),
            new DefaultProcessExecutor(new TestConsole()),
            Optional.empty(),
            Optional.empty());
  }

  private ArtifactConfig newConfig() {
    ArtifactConfig config = new ArtifactConfig();
    config.buckRepoRoot = buckRepoRoot.toString();
    config.thirdParty = thirdPartyRelative.toString();
    config.mavenLocalRepo = localRepo.toString();
    return config;
  }

  private void resolveWithArtifacts(String... artifacts)
      throws URISyntaxException, IOException, RepositoryException, ExecutionException,
          InterruptedException {
    ArtifactConfig config = newConfig();
    config.repositories.add(new Repository(httpd.getUri("/").toString()));
    config.artifacts.addAll(Arrays.asList(artifacts));
    config.visibility.add("PUBLIC");
    new Resolver(config).resolve(config.artifacts);
  }

  @Test
  public void shouldResolveTransitiveDependencyAndIncludeLibraryOnlyOnce() throws Exception {
    resolveWithArtifacts("com.example:A-depends-on-B-and-C:jar:1.0");
    AbsPath groupDir = thirdParty.resolve("example");
    assertTrue(Files.exists(groupDir.getPath()));
    assertTrue(Files.exists(groupDir.resolve("D-depends-on-none-2.0.jar").getPath()));
    assertFalse(Files.exists(groupDir.resolve("D-depends-on-none-1.0.jar").getPath()));
  }

  @Test
  public void shouldResolveTransitiveDependencyAndIncludeLibraryOnlyOnceViaPom() throws Exception {
    resolveWithArtifacts(
        repo.resolve("com/example/A-depends-on-B-and-C/1.0/A-depends-on-B-and-C-1.0.pom")
            .toString());
    AbsPath groupDir = thirdParty.resolve("example");
    assertTrue(Files.exists(groupDir.getPath()));
    assertTrue(Files.exists(groupDir.resolve("D-depends-on-none-2.0.jar").getPath()));
    assertFalse(Files.exists(groupDir.resolve("D-depends-on-none-1.0.jar").getPath()));
  }

  @Test
  public void shouldSetUpAPrivateLibraryIfGivenAMavenCoordWithoutDeps() throws Exception {
    resolveWithArtifacts("com.example:no-deps:jar:1.0");

    AbsPath groupDir = thirdParty.resolve("example");
    assertTrue(Files.exists(groupDir.getPath()));

    Path original = repo.resolve("com/example/no-deps/1.0/no-deps-1.0.jar");
    HashCode expected = MorePaths.asByteSource(original).hash(Hashing.sha1());
    AbsPath jarFile = groupDir.resolve("no-deps-1.0.jar");
    HashCode seen = MorePaths.asByteSource(jarFile.getPath()).hash(Hashing.sha1());
    assertEquals(expected, seen);

    TwoArraysImmutableHashMap<String, RawTargetNode> rules =
        buildFileParser.getManifest(groupDir.resolve("BUCK")).getTargets();

    assertEquals(1, rules.size());
    RawTargetNode rule = Iterables.getOnlyElement(rules.values());
    // Name is derived from the project identifier
    assertEquals("no-deps", rule.getBySnakeCase("name"));

    // The binary jar should be set
    assertEquals("no-deps-1.0.jar", rule.getBySnakeCase("binary_jar"));

    // There was no source jar in the repo
    assertNull(rule.getBySnakeCase("source_jar"));

    // It's a library that's requested on the CLI, so it gets full visibility.
    assertEquals(ImmutableList.of("PUBLIC"), rule.getVisibility());

    // And it doesn't depend on anything
    assertNull(rule.getBySnakeCase("deps"));
  }

  @Test
  public void shouldIncludeSourceJarIfOneIsPresent() throws Exception {
    resolveWithArtifacts("com.example:with-sources:jar:1.0");

    AbsPath groupDir = thirdParty.resolve("example");
    TwoArraysImmutableHashMap<String, RawTargetNode> rules =
        buildFileParser.getManifest(groupDir.resolve("BUCK")).getTargets();

    RawTargetNode rule = Iterables.getOnlyElement(rules.values());
    assertEquals("with-sources-1.0-sources.jar", rule.getBySnakeCase("source_jar"));
  }

  @Test
  public void shouldSetVisibilityOfTargetToGiveDependenciesAccess() throws Exception {
    resolveWithArtifacts("com.example:with-deps:jar:1.0");

    Path exampleDir = thirdPartyRelative.resolve("example");
    RawTargetNode withDeps =
        Iterables.getOnlyElement(
            buildFileParser
                .getManifest(buckRepoRoot.resolve(exampleDir).resolve("BUCK"))
                .getTargets()
                .values());
    Path otherDir = thirdPartyRelative.resolve("othercorp");
    RawTargetNode noDeps =
        Iterables.getOnlyElement(
            buildFileParser
                .getManifest(buckRepoRoot.resolve(otherDir).resolve("BUCK"))
                .getTargets()
                .values());

    List<String> visibility = noDeps.getVisibility();
    assertEquals(1, visibility.size());
    assertEquals(
        ImmutableList.of(
            String.format("//%s:with-deps", PathFormatter.pathWithUnixSeparators(exampleDir))),
        visibility);
    assertNull(noDeps.getBySnakeCase("deps"));

    assertEquals(ImmutableList.of("PUBLIC"), withDeps.getVisibility());
    @SuppressWarnings("unchecked")
    List<String> deps = (List<String>) withDeps.getBySnakeCase("deps");
    assertEquals(1, deps.size());
    assertEquals(
        ImmutableList.of(
            String.format("//%s:no-deps", PathFormatter.pathWithUnixSeparators(otherDir))),
        deps);
  }

  @Test
  public void shouldOmitTargetsInTheSameBuildFileInVisibilityArguments() throws Exception {
    resolveWithArtifacts("com.example:deps-in-same-project:jar:1.0");

    Path exampleDir = thirdPartyRelative.resolve("example");
    TwoArraysImmutableHashMap<String, RawTargetNode> allTargets =
        buildFileParser.getManifest(buckRepoRoot.resolve(exampleDir).resolve("BUCK")).getTargets();

    assertEquals(2, allTargets.size());

    RawTargetNode noDeps = allTargets.get("no-deps");

    assertNotNull(noDeps);

    // Although the "deps-in-same-project" could be in the visibility param, it doesn't need to be
    // because it's declared in the same build file.
    assertNull(noDeps.getBySnakeCase("visibility"));
  }

  @Test
  public void shouldNotDownloadOlderJar() throws Exception {
    AbsPath existingNewerJar = thirdParty.resolve("example/no-deps-1.1.jar");
    Files.createDirectories(existingNewerJar.getParent().getPath());
    Files.copy(repo.resolve("com/example/no-deps/1.0/no-deps-1.0.jar"), existingNewerJar.getPath());

    AbsPath groupDir = thirdParty.resolve("example");
    AbsPath repoOlderJar = groupDir.resolve("no-deps-1.0.jar");
    assertFalse(Files.exists(repoOlderJar.getPath()));

    resolveWithArtifacts("com.example:no-deps:jar:1.0");

    assertTrue(Files.exists(groupDir.getPath()));

    // assert newer jar is in the third-party dir
    assertTrue(Files.exists(existingNewerJar.getPath()));
    assertFalse(Files.exists(repoOlderJar.getPath()));

    // assert BUCK file was created
    assertTrue(Files.exists(groupDir.resolve("BUCK").getPath()));
  }

  @Test
  public void shouldDetectNewestJar() throws Exception {
    AbsPath groupDir = thirdParty.resolve("example");
    AbsPath existingNewerJar = groupDir.resolve("no-deps-1.1.jar");
    AbsPath existingNewestJar = groupDir.resolve("no-deps-1.2.jar");
    Files.createDirectories(groupDir.getPath());
    Path sourceJar = repo.resolve("com/example/no-deps/1.0/no-deps-1.0.jar");
    Files.copy(sourceJar, existingNewerJar.getPath());
    Files.copy(sourceJar, existingNewestJar.getPath());

    Artifact artifact = new DefaultArtifact("com.example", "no-deps", "jar", "1.0");
    Optional<Path> result =
        new Resolver(newConfig()).getNewerVersionFile(artifact, groupDir.getPath());

    assertTrue(result.isPresent());
    assertThat(result.get(), equalTo(existingNewestJar.getPath()));
  }
}
