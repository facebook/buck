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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.file.ExplodingDownloader;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.PrebuiltJarDescription;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.Description;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ResolverIntegrationTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private static HttpdForTests httpd;
  private static ProjectBuildFileParser buildFileParser;
  private static Path repo;
  private Path buckRepoRoot;
  private Path thirdParty;
  private Path thirdPartyRelative;
  private Path localRepo;
  private Resolver resolver;

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
    FakeBuckConfig buckConfig = new FakeBuckConfig();
    ParserConfig parserConfig = new ParserConfig(buckConfig);
    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(
        buckConfig,
        new ExecutableFinder());

    ImmutableSet<Description<?>> descriptions = ImmutableSet.of(
        new RemoteFileDescription(new ExplodingDownloader()),
        new PrebuiltJarDescription());

    DefaultProjectBuildFileParserFactory parserFactory = new DefaultProjectBuildFileParserFactory(
        filesystem.getRootPath(),
        pythonBuckConfig.getPythonInterpreter(),
        parserConfig.getAllowEmptyGlobs(),
        parserConfig.getBuildFileName(),
        parserConfig.getDefaultIncludes(),
        descriptions);
    buildFileParser = parserFactory.createParser(
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance());
  }

  @AfterClass
  public static void closeParser() throws BuildFileParseException, InterruptedException {
    buildFileParser.close();
  }

  @Before
  public void setUpRepos() throws Exception {
    buckRepoRoot = temp.newFolder().toPath();
    thirdPartyRelative = Paths.get("third-party").resolve("java");
    thirdParty = buckRepoRoot.resolve(thirdPartyRelative);
    localRepo = temp.newFolder().toPath();
    resolver = new Resolver(
        buckRepoRoot,
        thirdPartyRelative,
        localRepo,
        httpd.getUri("/").toString());
  }

  @Test
  public void shouldSetUpAPrivateLibraryIfGivenAMavenCoordWithoutDeps() throws Exception {
    resolver.resolve("com.example:no-deps:jar:1.0");

    Path groupDir = thirdParty.resolve("example");
    assertTrue(Files.exists(groupDir));

    Path original = repo.resolve("com/example/no-deps/1.0/no-deps-1.0.jar");
    HashCode expected = MorePaths.asByteSource(original).hash(Hashing.sha1());
    Path jarFile = groupDir.resolve("no-deps-1.0.jar");
    HashCode seen = MorePaths.asByteSource(jarFile).hash(Hashing.sha1());
    assertEquals(expected, seen);

    List<Map<String, Object>> rules = buildFileParser.getAll(groupDir.resolve("BUCK"));

    assertEquals(1, rules.size());
    Map<String, Object> rule = rules.get(0);
    // Name is derived from the project identifier
    assertEquals("no-deps", rule.get("name"));

    // The binary jar should be set
    assertEquals("no-deps-1.0.jar", rule.get("binaryJar"));

    // There was no source jar in the repo
    assertTrue(rule.containsKey("sourceJar"));
    assertNull(rule.get("sourceJar"));

    // Nothing depends on this, so it's not visible
    assertEquals(ImmutableList.of(), rule.get("visibility"));

    // And it doesn't depend on anything
    assertEquals(ImmutableList.of(), rule.get("deps"));
  }

  @Test
  public void shouldIncludeSourceJarIfOneIsPresent() throws Exception {
    resolver.resolve("com.example:with-sources:jar:1.0");

    Path groupDir = thirdParty.resolve("example");
    List<Map<String, Object>> rules = buildFileParser.getAll(groupDir.resolve("BUCK"));

    Map<String, Object> rule = rules.get(0);
    assertEquals("with-sources-1.0-sources.jar", rule.get("sourceJar"));
  }

  @Test
  public void shouldSetVisibilityOfTargetToGiveDependenciesAccess() throws Exception {
    resolver.resolve("com.example:with-deps:jar:1.0");

    Path exampleDir = thirdPartyRelative.resolve("example");
    Map<String, Object> withDeps =
        buildFileParser.getAll(buckRepoRoot.resolve(exampleDir).resolve("BUCK")).get(0);
    Path otherDir = thirdPartyRelative.resolve("othercorp");
    Map<String, Object> noDeps =
        buildFileParser.getAll(buckRepoRoot.resolve(otherDir).resolve("BUCK")).get(0);

    @SuppressWarnings("unchecked")
    List<String> visibility = (List<String>) noDeps.get("visibility");
    assertEquals(1, visibility.size());
    assertEquals(ImmutableList.of(String.format("//%s:with-deps", exampleDir)), visibility);
    assertEquals(ImmutableList.of(), noDeps.get("deps"));

    assertEquals(ImmutableList.of(), withDeps.get("visibility"));
    @SuppressWarnings("unchecked")
    List<String> deps = (List<String>) withDeps.get("deps");
    assertEquals(1, deps.size());
    assertEquals(ImmutableList.of(String.format("//%s:no-deps", otherDir)), deps);
  }

  @Test
  public void shouldOmitTargetsInTheSameBuildFileInVisibilityArguments() throws Exception {
    resolver.resolve("com.example:deps-in-same-project:jar:1.0");

    Path exampleDir = thirdPartyRelative.resolve("example");
    List<Map<String, Object>> allTargets = buildFileParser.getAll(
        buckRepoRoot.resolve(exampleDir).resolve(
            "BUCK"));

    assertEquals(2, allTargets.size());

    Map<String, Object> noDeps = null;
    for (Map<String, Object> target : allTargets) {
      if ("no-deps".equals(target.get("name"))) {
        noDeps = target;
        break;
      }
    }
    assertNotNull(noDeps);

    // Although the "deps-in-same-project" could be in the visibility param, it doesn't need to be
    // because it's declared in the same build file.
    assertEquals(0, ((Collection<?>) noDeps.get("visibility")).size());
  }

  @Test
  public void shouldNotDownloadOlderJar() throws Exception {
    Path existingNewerJar = thirdParty.resolve("example/no-deps-1.1.jar");
    Files.createDirectories(existingNewerJar.getParent());
    Files.copy(
        repo.resolve("com/example/no-deps/1.0/no-deps-1.0.jar"),
        existingNewerJar);

    Path groupDir = thirdParty.resolve("example");
    Path repoOlderJar = groupDir.resolve("no-deps-1.0.jar");
    assertFalse(Files.exists(repoOlderJar));

    resolver.resolve("com.example:no-deps:jar:1.0");

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
    Optional<Path> result = resolver.getNewerVersionFile(artifact, groupDir);

    assertTrue(result.isPresent());
    assertThat(result.get(), equalTo(existingNewestJar));
  }
}
