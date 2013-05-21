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

package com.facebook.buck.cli;

import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.JavaTestRule;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.easymock.EasyMock;
import org.hamcrest.collection.IsIterableContainingInAnyOrder;
import org.hamcrest.collection.IsIterableContainingInOrder;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.util.List;

public class TestCommandTest {

  private static ImmutableSortedSet<String> pathsFromRoot;
  private static ImmutableSet<String> pathElements;

  @BeforeClass
  public static void setUp() {
    pathsFromRoot = ImmutableSortedSet.of("java/");
    pathElements = ImmutableSet.of("src", "src-gen");
  }

  /**
   * If the source paths specified are all generated files, then our path to source folder
   * should be absent.
   */
  @Test
  public void testGeneratedSourceFile() {
    String pathToGenFile = GEN_DIR + "/GeneratedFile.java";
    assertTrue(JavaTestRule.isGeneratedFile(pathToGenFile));

    ImmutableSortedSet<String> javaSrcs = ImmutableSortedSet.of(pathToGenFile);

    JavaLibraryRule javaLibraryRule = EasyMock.createMock(JavaLibraryRule.class);
    EasyMock.expect(javaLibraryRule.getJavaSrcs())
        .andReturn(ImmutableSortedSet.copyOf(javaSrcs));

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        EasyMock.createMock(DefaultJavaPackageFinder.class);

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);

    Object[] mocks = new Object[] {projectFilesystem, defaultJavaPackageFinder, javaLibraryRule};
    EasyMock.replay(mocks);

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibraryRule, Optional.of(defaultJavaPackageFinder), projectFilesystem);

    assertTrue("No path should be returned if the library contains only generated files.",
        result.isEmpty());

    EasyMock.verify(mocks);
  }

  /**
   * If the source paths specified are all for non-generated files then we should return
   * the correct source folder corresponding to a non-generated source path.
   */
  @Test
  public void testNonGeneratedSourceFile() {
    String pathToNonGenFile = "package/src/SourceFile1.java";
    assertFalse(JavaTestRule.isGeneratedFile(pathToNonGenFile));

    ImmutableSortedSet<String> javaSrcs = ImmutableSortedSet.of(pathToNonGenFile);

    File parentFile = EasyMock.createMock(File.class);
    EasyMock.expect(parentFile.getName()).andReturn("src");
    EasyMock.expect(parentFile.getPath()).andReturn("package/src");

    File sourceFile = EasyMock.createMock(File.class);
    EasyMock.expect(sourceFile.getParentFile()).andReturn(parentFile);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        EasyMock.createMock(DefaultJavaPackageFinder.class);
    EasyMock.expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);
    EasyMock.expect(defaultJavaPackageFinder.getPathElements()).andReturn(pathElements);

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.getFileForRelativePath(pathToNonGenFile))
        .andReturn(sourceFile);

    JavaLibraryRule javaLibraryRule = EasyMock.createMock(JavaLibraryRule.class);
    EasyMock.expect(javaLibraryRule.getJavaSrcs())
        .andReturn(ImmutableSortedSet.copyOf(javaSrcs));

    Object[] mocks = new Object[] {
        parentFile,
        sourceFile,
        defaultJavaPackageFinder,
        projectFilesystem,
        javaLibraryRule};
    EasyMock.replay(mocks);

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibraryRule, Optional.of(defaultJavaPackageFinder), projectFilesystem);

    assertEquals("All non-generated source files are under one source folder.",
        ImmutableSet.of("package/src/"), result);

    EasyMock.verify(mocks);
  }

  /**
   * If the source paths specified are from the new unified source folder then we should return
   * the correct source folder corresponding to the unified source path.
   */
  @Test
  public void testUnifiedSourceFile() {
    String pathToNonGenFile = "java/package/SourceFile1.java";
    assertFalse(JavaTestRule.isGeneratedFile(pathToNonGenFile));

    ImmutableSortedSet<String> javaSrcs = ImmutableSortedSet.of(pathToNonGenFile);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        EasyMock.createMock(DefaultJavaPackageFinder.class);
    EasyMock.expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);

    JavaLibraryRule javaLibraryRule = EasyMock.createMock(JavaLibraryRule.class);
    EasyMock.expect(javaLibraryRule.getJavaSrcs())
        .andReturn(ImmutableSortedSet.copyOf(javaSrcs));

    Object[] mocks = new Object[] {defaultJavaPackageFinder, projectFilesystem, javaLibraryRule};
    EasyMock.replay(mocks);

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibraryRule, Optional.of(defaultJavaPackageFinder), projectFilesystem);

    assertEquals("All non-generated source files are under one source folder.",
        ImmutableSet.of("java/"), result);

    EasyMock.verify(mocks);
  }

  /**
   * If the source paths specified contains one source path to a non-generated file then
   * we should return the correct source folder corresponding to that non-generated source path.
   * Especially when the generated file comes first in the ordered set.
   */
  @Test
  public void testMixedSourceFile() {
    String pathToGenFile = (GEN_DIR + "/com/facebook/GeneratedFile.java");
    String pathToNonGenFile1 = ("package/src/SourceFile1.java");
    String pathToNonGenFile2 = ("package/src-gen/SourceFile2.java");

    ImmutableSortedSet<String> javaSrcs = ImmutableSortedSet.of(
        pathToGenFile, pathToNonGenFile1, pathToNonGenFile2);

    File parentFile1 = EasyMock.createMock(File.class);
    EasyMock.expect(parentFile1.getName()).andReturn("src");
    EasyMock.expect(parentFile1.getPath()).andReturn("package/src");

    File sourceFile1 = EasyMock.createMock(File.class);
    EasyMock.expect(sourceFile1.getParentFile()).andReturn(parentFile1);

    File parentFile2 = EasyMock.createMock(File.class);
    EasyMock.expect(parentFile2.getName()).andReturn("src");
    EasyMock.expect(parentFile2.getPath()).andReturn("package/src-gen");

    File sourceFile2 = EasyMock.createMock(File.class);
    EasyMock.expect(sourceFile2.getParentFile()).andReturn(parentFile2);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        EasyMock.createMock(DefaultJavaPackageFinder.class);
    EasyMock.expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot).times(2);
    EasyMock.expect(defaultJavaPackageFinder.getPathElements()).andReturn(pathElements).times(2);

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.getFileForRelativePath(pathToNonGenFile1))
        .andReturn(sourceFile1);
    EasyMock.expect(projectFilesystem.getFileForRelativePath(pathToNonGenFile2))
        .andReturn(sourceFile2);

    JavaLibraryRule javaLibraryRule = EasyMock.createMock(JavaLibraryRule.class);
    EasyMock.expect(javaLibraryRule.getJavaSrcs())
        .andReturn(ImmutableSortedSet.copyOf(javaSrcs));

    Object[] mocks = new Object[] {
        parentFile1,
        sourceFile1,
        parentFile2,
        sourceFile2,
        defaultJavaPackageFinder,
        projectFilesystem,
        javaLibraryRule};
    EasyMock.replay(mocks);

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibraryRule, Optional.of(defaultJavaPackageFinder), projectFilesystem);

    assertEquals("The non-generated source files are under two different source folders.",
        ImmutableSet.of("package/src-gen/", "package/src/"), result);

    EasyMock.verify(mocks);
  }

  private TestCommandOptions getOptions(String...args) throws CmdLineException {
    TestCommandOptions options = new TestCommandOptions(BuckConfig.emptyConfig());
    new CmdLineParserAdditionalOptions(options).parseArgument(args);
    return options;
  }

  private DependencyGraph createDependencyGraphFromBuildRules(Iterable<? extends BuildRule> rules) {
    MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<BuildRule>();
    for (BuildRule rule : rules) {
      for (BuildRule dep : rule.getDeps()) {
        graph.addEdge(rule, dep);
      }
    }

    return new DependencyGraph(graph);
  }

  @Test
  public void testGetCandidateRulesByIncludedLabels() throws CmdLineException {
    TestRule rule1 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("windows", "linux"),
        BuildTargetFactory.newInstance("//:for"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    TestRule rule2 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("android"),
        BuildTargetFactory.newInstance("//:teh"),
        ImmutableSortedSet.<BuildRule>of(rule1),
        ImmutableSet.<BuildTargetPattern>of());

    TestRule rule3 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("windows"),
        BuildTargetFactory.newInstance("//:lulz"),
        ImmutableSortedSet.<BuildRule>of(rule2),
        ImmutableSet.<BuildTargetPattern>of());

    Iterable<TestRule> rules = Lists.newArrayList(rule1, rule2, rule3);
    DependencyGraph graph = createDependencyGraphFromBuildRules(rules);
    TestCommandOptions options = getOptions("--include", "linux", "windows");

    Iterable<TestRule> result = TestCommand.getCandidateRulesByIncludedLabels(
        graph, options.getIncludedLabels());
    assertThat(result, IsIterableContainingInAnyOrder.containsInAnyOrder(rule1, rule3));
  }

  @Test
  public void testFilterBuilds() throws CmdLineException {
    TestCommandOptions options = getOptions("--exclude", "linux", "windows");

    TestRule rule1 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("windows", "linux"),
        BuildTargetFactory.newInstance("//:for"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    TestRule rule2 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("android"),
        BuildTargetFactory.newInstance("//:teh"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    TestRule rule3 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("windows"),
        BuildTargetFactory.newInstance("//:lulz"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    List<TestRule> testRules = ImmutableList.of(rule1, rule2, rule3);

    Iterable<TestRule> result = TestCommand.filterTestRules(options, testRules);
    assertThat(result, IsIterableContainingInOrder.contains(rule2));
  }
}
