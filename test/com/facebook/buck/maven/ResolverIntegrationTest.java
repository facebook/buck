/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.maven;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.impl.PythonInterpreterFromConfig;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.file.downloader.impl.ExplodingDownloader;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.PrebuiltJarDescription;
import com.facebook.buck.maven.aether.Repository;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.PythonDslProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class ResolverIntegrationTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  private static HttpdForTests httpd;
  private static PythonDslProjectBuildFileParser buildFileParser;
  private static Path repo;
  private Path buckRepoRoot;
  private Path thirdParty;
  private Path thirdPartyRelative;
  private Path localRepo;

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

  @BeforeClass
  public static void createParser() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(buckConfig);

    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(Downloader.DEFAULT_NAME, new ExplodingDownloader())
            .build();
    ImmutableSet<DescriptionWithTargetGraph<?>> descriptions =
        ImmutableSet.of(new RemoteFileDescription(toolchainProvider), new PrebuiltJarDescription());

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
            Optional.empty());
  }

  @AfterClass
  public static void closeParser()
      throws BuildFileParseException, InterruptedException, IOException {
    buildFileParser.close();
  }

  @Before
  public void setUpRepos() throws Exception {
    buckRepoRoot = temp.newFolder();
    thirdPartyRelative = Paths.get("third-party").resolve("java");
    thirdParty = buckRepoRoot.resolve(thirdPartyRelative);
    localRepo = temp.newFolder();
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
    Path groupDir = thirdParty.resolve("example");
    assertTrue(Files.exists(groupDir));
    assertTrue(Files.exists(groupDir.resolve("D-depends-on-none-2.0.jar")));
    assertFalse(Files.exists(groupDir.resolve("D-depends-on-none-1.0.jar")));
  }

  @Test
  public void shouldResolveTransitiveDependencyAndIncludeLibraryOnlyOnceViaPom() throws Exception {
    resolveWithArtifacts(
        repo.resolve("com/example/A-depends-on-B-and-C/1.0/A-depends-on-B-and-C-1.0.pom")
            .toString());
    Path groupDir = thirdParty.resolve("example");
    assertTrue(Files.exists(groupDir));
    assertTrue(Files.exists(groupDir.resolve("D-depends-on-none-2.0.jar")));
    assertFalse(Files.exists(groupDir.resolve("D-depends-on-none-1.0.jar")));
  }

  @Test
  public void shouldSetUpAPrivateLibraryIfGivenAMavenCoordWithoutDeps() throws Exception {
    resolveWithArtifacts("com.example:no-deps:jar:1.0");

    Path groupDir = thirdParty.resolve("example");
    assertTrue(Files.exists(groupDir));

    Path original = repo.resolve("com/example/no-deps/1.0/no-deps-1.0.jar");
    HashCode expected = MorePaths.asByteSource(original).hash(Hashing.sha1());
    Path jarFile = groupDir.resolve("no-deps-1.0.jar");
    HashCode seen = MorePaths.asByteSource(jarFile).hash(Hashing.sha1());
    assertEquals(expected, seen);

    Map<String, Map<String, Object>> rules =
        buildFileParser.getBuildFileManifest(groupDir.resolve("BUCK")).getTargets();

    assertEquals(1, rules.size());
    Map<String, Object> rule = Iterables.getOnlyElement(rules.values());
    // Name is derived from the project identifier
    assertEquals("no-deps", rule.get("name"));

    // The binary jar should be set
    assertEquals("no-deps-1.0.jar", rule.get("binaryJar"));

    // There was no source jar in the repo
    assertNull(rule.get("sourceJar"));

    // It's a library that's requested on the CLI, so it gets full visibility.
    assertEquals(ImmutableList.of("PUBLIC"), rule.get("visibility"));

    // And it doesn't depend on anything
    assertNull(rule.get("deps"));
  }

  @Test
  public void shouldIncludeSourceJarIfOneIsPresent() throws Exception {
    resolveWithArtifacts("com.example:with-sources:jar:1.0");

    Path groupDir = thirdParty.resolve("example");
    Map<String, Map<String, Object>> rules =
        buildFileParser.getBuildFileManifest(groupDir.resolve("BUCK")).getTargets();

    Map<String, Object> rule = Iterables.getOnlyElement(rules.values());
    assertEquals("with-sources-1.0-sources.jar", rule.get("sourceJar"));
  }

  @Test
  public void shouldSetVisibilityOfTargetToGiveDependenciesAccess() throws Exception {
    resolveWithArtifacts("com.example:with-deps:jar:1.0");

    Path exampleDir = thirdPartyRelative.resolve("example");
    Map<String, Object> withDeps =
        Iterables.getOnlyElement(
            buildFileParser
                .getBuildFileManifest(buckRepoRoot.resolve(exampleDir).resolve("BUCK"))
                .getTargets()
                .values());
    Path otherDir = thirdPartyRelative.resolve("othercorp");
    Map<String, Object> noDeps =
        Iterables.getOnlyElement(
            buildFileParser
                .getBuildFileManifest(buckRepoRoot.resolve(otherDir).resolve("BUCK"))
                .getTargets()
                .values());

    @SuppressWarnings("unchecked")
    List<String> visibility = (List<String>) noDeps.get("visibility");
    assertEquals(1, visibility.size());
    assertEquals(
        ImmutableList.of(
            String.format("//%s:with-deps", MorePaths.pathWithUnixSeparators(exampleDir))),
        visibility);
    assertNull(noDeps.get("deps"));

    assertEquals(ImmutableList.of("PUBLIC"), withDeps.get("visibility"));
    @SuppressWarnings("unchecked")
    List<String> deps = (List<String>) withDeps.get("deps");
    assertEquals(1, deps.size());
    assertEquals(
        ImmutableList.of(String.format("//%s:no-deps", MorePaths.pathWithUnixSeparators(otherDir))),
        deps);
  }

  @Test
  public void shouldOmitTargetsInTheSameBuildFileInVisibilityArguments() throws Exception {
    resolveWithArtifacts("com.example:deps-in-same-project:jar:1.0");

    Path exampleDir = thirdPartyRelative.resolve("example");
    Map<String, Map<String, Object>> allTargets =
        buildFileParser
            .getBuildFileManifest(buckRepoRoot.resolve(exampleDir).resolve("BUCK"))
            .getTargets();

    assertEquals(2, allTargets.size());

    Map<String, Object> noDeps = allTargets.get("no-deps");

    assertNotNull(noDeps);

    // Although the "deps-in-same-project" could be in the visibility param, it doesn't need to be
    // because it's declared in the same build file.
    assertNull(noDeps.get("visibility"));
  }

  @Test
  public void shouldNotDownloadOlderJar() throws Exception {
    Path existingNewerJar = thirdParty.resolve("example/no-deps-1.1.jar");
    Files.createDirectories(existingNewerJar.getParent());
    Files.copy(repo.resolve("com/example/no-deps/1.0/no-deps-1.0.jar"), existingNewerJar);

    Path groupDir = thirdParty.resolve("example");
    Path repoOlderJar = groupDir.resolve("no-deps-1.0.jar");
    assertFalse(Files.exists(repoOlderJar));

    resolveWithArtifacts("com.example:no-deps:jar:1.0");

    assertTrue(Files.exists(groupDir));

    // assert newer jar is in the third-party dir
    assertTrue(Files.exists(existingNewerJar));
    assertFalse(Files.exists(repoOlderJar));

    // assert BUCK file was created
    assertTrue(Files.exists(groupDir.resolve("BUCK")));
  }

  @Test
  public void shouldDetectNewestJar() throws Exception {
    Path groupDir = thirdParty.resolve("example");
    Path existingNewerJar = groupDir.resolve("no-deps-1.1.jar");
    Path existingNewestJar = groupDir.resolve("no-deps-1.2.jar");
    Files.createDirectories(groupDir);
    Path sourceJar = repo.resolve("com/example/no-deps/1.0/no-deps-1.0.jar");
    Files.copy(sourceJar, existingNewerJar);
    Files.copy(sourceJar, existingNewestJar);

    Artifact artifact = new DefaultArtifact("com.example", "no-deps", "jar", "1.0");
    Optional<Path> result = new Resolver(newConfig()).getNewerVersionFile(artifact, groupDir);

    assertTrue(result.isPresent());
    assertThat(result.get(), equalTo(existingNewestJar));
  }
}
