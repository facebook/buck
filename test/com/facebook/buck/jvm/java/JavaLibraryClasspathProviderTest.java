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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class JavaLibraryClasspathProviderTest extends AbiCompilationModeTest {

  private TargetNode<?, ?> aNode;
  private TargetNode<?, ?> bNode;
  private TargetNode<?, ?> cNode;
  private TargetNode<?, ?> dNode;
  private TargetNode<?, ?> eNode;
  private TargetNode<?, ?> zNode;

  private BuildRule a;
  private BuildRule b;
  private BuildRule c;
  private BuildRule d;
  private BuildRule e;
  private BuildRule z;
  private SourcePathResolver resolver;
  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws Exception {
    filesystem = new FakeProjectFilesystem();

    // Create our target graph. All nodes are JavaLibrary except b
    // (exports c) az (no exports)
    //            /  \
    // (non java) b    c (exports e)
    //           |    |
    //           d    e
    dNode = makeRule("//foo:d", ImmutableSet.of("foo", "d.java"), ImmutableSet.of(), filesystem);

    bNode =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//foo:b"))
            .setSrcs(ImmutableList.of(FakeSourcePath.of(filesystem, "foo/b.java")))
            .setCmd("echo $(classpath //foo:d")
            .setOut("b.out")
            .build();

    eNode = makeRule("//foo:e", ImmutableSet.of("foo", "e.java"), ImmutableSet.of(), filesystem);

    // exported
    cNode =
        makeRule(
            "//foo:c",
            ImmutableSet.of("foo", "c.java"),
            ImmutableSet.of(eNode),
            ImmutableSet.of(eNode), // exported
            filesystem);

    aNode =
        makeRule(
            "//foo:a",
            ImmutableSet.of("foo", "a.java"),
            ImmutableSet.of(bNode, cNode),
            ImmutableSet.of(cNode),
            filesystem);

    zNode =
        makeRule(
            "//foo:z", ImmutableSet.of("foo", "a.java"), ImmutableSet.of(bNode, cNode), filesystem);

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(aNode, bNode, cNode, dNode, eNode, zNode);
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    resolver = DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));

    a = ruleResolver.requireRule(aNode.getBuildTarget());
    b = ruleResolver.requireRule(bNode.getBuildTarget());
    c = ruleResolver.requireRule(cNode.getBuildTarget());
    d = ruleResolver.requireRule(dNode.getBuildTarget());
    e = ruleResolver.requireRule(eNode.getBuildTarget());
    z = ruleResolver.requireRule(zNode.getBuildTarget());
  }

  @Test
  public void getOutputClasspathEntries() {
    JavaLibrary aLib = (JavaLibrary) a;
    assertEquals(
        ImmutableSet.of(
            getFullOutput(a),
            getFullOutput(c), // a exports c
            getFullOutput(e) // c exports e
            ),
        JavaLibraryClasspathProvider.getOutputClasspathJars(
                aLib, Optional.of(aLib.getSourcePathToOutput()))
            .stream()
            .map(resolver::getAbsolutePath)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void getClasspathFromLibraries() {
    assertEquals(
        ImmutableSet.of(getFullOutput(a), getFullOutput(c), getFullOutput(e)),
        // b is non-java so b and d do not appear
        JavaLibraryClasspathProvider.getClasspathsFromLibraries(
                JavaLibraryClasspathProvider.getClasspathDeps(ImmutableSet.of(a)))
            .stream()
            .map(resolver::getAbsolutePath)
            .collect(ImmutableSet.toImmutableSet()));

    assertEquals(
        ImmutableSet.of(
            getFullOutput(c),
            getFullOutput(e), // c exports e
            getFullOutput(d)),
        JavaLibraryClasspathProvider.getClasspathsFromLibraries(
                JavaLibraryClasspathProvider.getClasspathDeps(ImmutableSet.of(c, d)))
            .stream()
            .map(resolver::getAbsolutePath)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void getClasspathDeps() {
    assertEquals(
        ImmutableSet.of(a, c, e),
        JavaLibraryClasspathProvider.getClasspathDeps(ImmutableSet.of(a)));

    assertEquals(
        ImmutableSet.of(d, c, e),
        JavaLibraryClasspathProvider.getClasspathDeps(ImmutableSet.of(d, c)));
  }

  @Test
  public void getTransitiveClasspaths() {
    JavaLibrary aLib = (JavaLibrary) a;
    assertEquals(
        ImmutableSet.builder()
            .add(getFullOutput(a))
            .add(getFullOutput(c)) // a exports c
            .add(getFullOutput(e)) // c exports e
            // b is non-java so b and d do not appear
            .build(),
        aLib.getTransitiveClasspaths()
            .stream()
            .map(resolver::getAbsolutePath)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void getTransitiveClasspathDeps() throws Exception {
    TargetNode<?, ?> noOutputNode =
        makeRule("//no:output", ImmutableSet.of(), ImmutableSet.of(zNode), filesystem);

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(aNode, bNode, cNode, dNode, eNode, zNode, noOutputNode);
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);

    JavaLibrary noOutput = (JavaLibrary) ruleResolver.requireRule(noOutputNode.getBuildTarget());

    assertEquals(
        "root does not appear if output jar not present.",
        ImmutableSet.of(c, e, z),
        JavaLibraryClasspathProvider.getTransitiveClasspathDeps(noOutput));

    assertEquals(
        "root does appear if output jar present.",
        ImmutableSet.of(z, c, e),
        JavaLibraryClasspathProvider.getTransitiveClasspathDeps((JavaLibrary) z));

    BuildRule mavenCoord =
        new JavaLibraryBuilder(
                BuildTargetFactory.newInstance("//has:output"),
                filesystem,
                HashCode.fromString("aaaa"))
            .setMavenCoords("com.example:buck:1.0")
            .addDep(z.getBuildTarget())
            .build(ruleResolver);

    assertEquals(
        "Does appear if no output jar but maven coordinate present.",
        ImmutableSet.of(z, c, e, mavenCoord),
        JavaLibraryClasspathProvider.getTransitiveClasspathDeps((JavaLibrary) mavenCoord));
  }

  @Test
  public void getJavaLibraryDeps() {
    assertThat(
        JavaLibraryClasspathProvider.getJavaLibraryDeps(ImmutableList.of(a, b, c, d, e)),
        Matchers.containsInAnyOrder(a, c, d, e));
  }

  private Path getFullOutput(BuildRule lib) {
    return resolver.getAbsolutePath(lib.getSourcePathToOutput());
  }

  private TargetNode<?, ?> makeRule(
      String target,
      Iterable<String> srcs,
      Iterable<TargetNode<?, ?>> deps,
      ProjectFilesystem filesystem)
      throws Exception {
    return makeRule(target, srcs, deps, null, filesystem);
  }

  private TargetNode<?, ?> makeRule(
      String target,
      Iterable<String> srcs,
      Iterable<TargetNode<?, ?>> deps,
      @Nullable Iterable<TargetNode<?, ?>> exportedDeps,
      ProjectFilesystem filesystem) {
    JavaLibraryBuilder builder;
    BuildTarget parsedTarget = BuildTargetFactory.newInstance(target);
    JavaBuckConfig testConfig = getJavaBuckConfigWithCompilationMode();
    builder = JavaLibraryBuilder.createBuilder(parsedTarget, testConfig);

    for (String src : srcs) {
      builder.addSrc(filesystem.getBuckPaths().getGenDir().resolve(src));
    }
    for (TargetNode<?, ?> dep : deps) {
      builder.addDep(dep.getBuildTarget());
    }

    if (exportedDeps != null) {
      for (TargetNode<?, ?> dep : exportedDeps) {
        builder.addExportedDep(dep.getBuildTarget());
      }
    }
    return builder.build();
  }
}
