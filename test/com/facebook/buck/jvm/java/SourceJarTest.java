package com.facebook.buck.jvm.java;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public class SourceJarTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void outputNameShouldIndicateThatTheOutputIsASrcJar() {
    BuildRuleResolver resolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());

    SourceJar rule = new SourceJar(
        new FakeBuildRuleParamsBuilder("//example:target").build(),
        "8",
        ImmutableSortedSet.of(),
        Optional.empty(),
        Optional.empty(),
        ImmutableSortedSet.of());
    resolver.addToIndex(rule);

    SourcePath output = rule.getSourcePathToOutput();

    assertNotNull(output);
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    assertThat(pathResolver.getRelativePath(output).toString(), endsWith(Javac.SRC_JAR));
  }

  @Test
  public void shouldIncludeSourcesFromBuildTargetsAndPlainPaths() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "src-jar",
        tmp);
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:lib#src");


    Zip zip = new Zip(output, /* for writing? */ false);
    Set<String> fileNames = zip.getFileNames();

    assertTrue(fileNames.contains("com/example/Direct.java"));
    assertTrue(fileNames.contains("com/example/Generated.java"));

    // output should not contain a transitive dep
    assertFalse(fileNames.contains("com/example/Transitive.java"));
  }

  @Test
  public void shouldNotIncludeNonJavaFiles() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "src-jar",
        tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:lib#src");


    Zip zip = new Zip(output, /* for writing? */ false);
    Set<String> fileNames = zip.getFileNames();

    assertFalse(fileNames.contains("com/example/hello.txt"));
  }

  @Test
  public void shouldBuildMavenisedSourceJars() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "src-jar",
        tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:lib#maven,src");


    Zip zip = new Zip(output, /* for writing? */ false);
    Set<String> fileNames = zip.getFileNames();

    // output should not contain any files from "//:mvn-dep"
    assertFalse(fileNames.contains("com/example/MavenSource.java"));

    // output should contain a transitive dep
    assertTrue(fileNames.contains("com/example/Transitive.java"));

    // output should contain a direct dep
    assertTrue(fileNames.contains("com/example/Direct.java"));
  }
}