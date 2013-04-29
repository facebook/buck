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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilder;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ParserTest {

  private File testBuildFile;
  private Parser testParser;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Before
  public void setUp() throws IOException {
    // Create a temp directory with some build files.
    File projectDirectoryRoot = tempDir.getRoot();
    tempDir.newFolder("java", "com", "facebook");

    testBuildFile = tempDir.newFile(
        "java/com/facebook/" + BuckConstant.BUILD_RULES_FILE_NAME);
    Files.write(
        "java_library(name = 'foo')\n" +
        "java_library(name = 'bar')\n",
        testBuildFile,
        Charsets.UTF_8);

    // Create a Parser.
    Ansi ansi = new Ansi();
    ProjectFilesystem filesystem = new ProjectFilesystem(projectDirectoryRoot);
    testParser = new Parser(filesystem,
        new BuildFileTree(ImmutableSet.<BuildTarget>of()),
        ansi);
  }

  /**
   * If a Parser is populated via {@link Parser#parseRawRules(java.util.List, RawRulePredicate)},
   * and a rule contains an erroneous dep to a non-existent rule, then it should throw an
   * appropriate message to help the user find the source of his error.
   */
  @Test
  public void testParseRawRulesWithBadDependency() throws NoSuchBuildTargetException, IOException {
    String nonExistentBuildTarget = "//testdata/com/facebook/feed:util";
    Map<String, Object> rawRule = ImmutableMap.<String, Object>of(
        "type", "java_library",
        "name", "feed",
        // A non-existent dependency: this is a user error that should be reported.
        "deps", ImmutableList.of(nonExistentBuildTarget),
        "buck_base_path", "testdata/com/facebook/feed/model");
    List<Map<String, Object>> ruleObjects = ImmutableList.of(rawRule);

    File projectDirectoryRoot = new File(".");
    Ansi ansi = new Ansi();
    ProjectFilesystem filesystem = new ProjectFilesystem(projectDirectoryRoot);
    Parser parser = new Parser(filesystem,
        new BuildFileTree(ImmutableSet.<BuildTarget>of()),
        ansi);

    RawRulePredicate predicate = RawRulePredicates.alwaysTrue();
    List<BuildTarget> targets = parser.parseRawRules(ruleObjects, predicate);
    BuildTarget expectedBuildTarget = new BuildTarget(
        new File("./testdata/com/facebook/feed/model/" + BuckConstant.BUILD_RULES_FILE_NAME),
        "//testdata/com/facebook/feed/model",
        "feed");
    assertEquals(ImmutableList.of(expectedBuildTarget), targets);

    try {
      parser.findAllTransitiveDependencies(targets, ImmutableList.<String>of());
      fail("Should have thrown a HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(
          String.format("No such build target: %s.", nonExistentBuildTarget),
          e.getHumanReadableErrorMessage());
    }
  }

  /**
   * Creates the following graph (assume all / and \ indicate downward pointing arrows):
   * <pre>
   *         A
   *       /   \
   *     B       C <----|
   *   /   \   /        |
   * D       E          |
   *   \   /            |
   *     F --------------
   * </pre>
   * Note that there is a circular dependency from C -> E -> F -> C that should be caught by the
   * parser.
   */
  @Test
  public void testCircularDependencyDetection() throws IOException, NoSuchBuildTargetException {
    // Mock out objects that are not critical to parsing.
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.getProjectRoot()).andReturn(new File("."));

    BuildTargetParser buildTargetParser = new BuildTargetParser(projectFilesystem) {
      @Override
      public BuildTarget parse(String buildTargetName, ParseContext parseContext)
          throws NoSuchBuildTargetException {
        return BuildTargetFactory.newInstance(buildTargetName);
      }
    };

    BuildFileTree buildFiles = EasyMock.createMock(BuildFileTree.class);
    Ansi ansi = new Ansi();
    EasyMock.replay(projectFilesystem, buildFiles);

    // Create the set of known build targets so the Parser does not have to exercise its parsing
    // logic, only its graph traversal algorithms.
    Map<String, BuildRuleBuilder> knownBuildTargets = createKnownBuildTargets();

    Parser parser = new Parser(projectFilesystem,
        buildFiles,
        ansi,
        buildTargetParser,
        knownBuildTargets);

    BuildTarget rootNode = BuildTargetFactory.newInstance("//:A");
    Iterable<BuildTarget> buildTargets = ImmutableSet.of(rootNode);
    Iterable<String> defaultIncludes = ImmutableList.of();
    try {
      parser.findAllTransitiveDependencies(buildTargets, defaultIncludes);
      fail("Should have thrown a HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("Cycle found: //:C -> //:E -> //:F -> //:C", e.getMessage());
    }

    EasyMock.verify(projectFilesystem, buildFiles);
  }

  @Test
  public void testParseBuildFilesForTargetsWithOverlappingTargets()
      throws IOException, NoSuchBuildTargetException {

    // Execute parseBuildFilesForTargets() with multiple targets that require parsing the same
    // build file.
    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/facebook",
        "foo", testBuildFile);
    BuildTarget barTarget = BuildTargetFactory.newInstance("//java/com/facebook",
        "bar", testBuildFile);
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    DependencyGraph graph = testParser.parseBuildFilesForTargets(buildTargets, defaultIncludes);
    BuildRule fooRule = graph.findBuildRuleByTarget(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = graph.findBuildRuleByTarget(barTarget);
    assertNotNull(barRule);
  }

  @Test
  public void testMissingBuildRuleInValidFile() throws IOException {
    // Execute parseBuildFilesForTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/facebook",
        "foo", testBuildFile);
    BuildTarget razTarget = BuildTargetFactory.newInstance("//java/com/facebook",
        "raz", testBuildFile);
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    try {
      testParser.parseBuildFilesForTargets(buildTargets, defaultIncludes);
    } catch (NoSuchBuildTargetException e) {
      assertEquals("No rule 'raz' found in java/com/facebook/BUCK",
          e.getHumanReadableErrorMessage());
      return;
    }
    fail("NoSuchBuildTargetException should be thrown");
  }

  private static Map<String, BuildRuleBuilder> createKnownBuildTargets() {
    Map<String, BuildRuleBuilder> knownBuildTargets =
        ImmutableMap.<String, BuildRuleBuilder>builder()
        .put("//:A", createBuildRuleBuilder("A", "B", "C"))
        .put("//:B", createBuildRuleBuilder("B", "D", "E"))
        .put("//:C", createBuildRuleBuilder("C", "E"))
        .put("//:D", createBuildRuleBuilder("D", "F"))
        .put("//:E", createBuildRuleBuilder("E", "F"))
        .put("//:F", createBuildRuleBuilder("F", "C"))
        .build();

    return knownBuildTargets;
  }

  private static BuildRuleBuilder createBuildRuleBuilder(String name, String... qualifiedDeps) {
    final BuildTarget buildTarget = BuildTargetFactory.newInstance("//:" + name);
    ImmutableSortedSet.Builder<String> depsBuilder = ImmutableSortedSet.naturalOrder();
    for (String dep : qualifiedDeps) {
      depsBuilder.add("//:" + dep);
    }
    final ImmutableSortedSet<String> deps = depsBuilder.build();

    return new BuildRuleBuilder() {

      @Override
      public BuildTarget getBuildTarget() {
        return buildTarget;
      }

      @Override
      public Set<String> getDeps() {
        return deps;
      }

      @Override
      public Set<BuildTargetPattern> getVisibilityPatterns() {
        return ImmutableSet.of();
      }

      @Override
      public BuildRule build(final Map<String, BuildRule> buildRuleIndex) {
        return new FakeBuildRule(
            BuildRuleType.JAVA_LIBRARY,
            buildTarget,
            ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(Iterables.transform(deps, new Function<String, BuildRule>() {
                @Override
                public BuildRule apply(String target) {
                  return buildRuleIndex.get(target);
                }
              }))
              .build(),
              ImmutableSet.<BuildTargetPattern>of());
      }
    };
  }
}
