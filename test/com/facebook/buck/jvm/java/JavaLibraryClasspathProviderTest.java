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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import javax.annotation.Nullable;

public class JavaLibraryClasspathProviderTest {

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
  private Path basePath;
  private Function<Path, SourcePath> sourcePathFunction;
  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws Exception {
    filesystem = new FakeProjectFilesystem();
    basePath = filesystem.getRootPath();

    // Create our target graph. All nodes are JavaLibrary except b
    // (exports c) az (no exports)
    //            /  \
    //(non java) b    c (exports e)
    //           |    |
    //           d    e
    dNode = makeRule("//foo:d",
        ImmutableSet.of("foo", "d.java"),
        ImmutableSet.of(),
        filesystem);

    sourcePathFunction = SourcePaths.toSourcePath(filesystem);
    bNode = GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//foo:b"))
        .setSrcs(ImmutableList.of(
            sourcePathFunction.apply(Paths.get("foo", "b.java"))))
        .setCmd("echo $(classpath //foo:d")
        .setOut("b.out")
        .build();

    eNode = makeRule("//foo:e",
        ImmutableSet.of("foo", "e.java"),
        ImmutableSet.of(),
        filesystem);

    // exported
    cNode = makeRule("//foo:c",
        ImmutableSet.of("foo", "c.java"),
        ImmutableSet.of(eNode),
        ImmutableSet.of(eNode),  // exported
        filesystem);

    aNode = makeRule("//foo:a",
        ImmutableSet.of("foo", "a.java"),
        ImmutableSet.of(bNode, cNode),
        ImmutableSet.of(cNode),
        filesystem);

    zNode = makeRule("//foo:z",
        ImmutableSet.of("foo", "a.java"),
        ImmutableSet.of(bNode, cNode),
        filesystem);

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(aNode, bNode, cNode, dNode, eNode, zNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    resolver = new SourcePathResolver(ruleResolver);

    a = ruleResolver.requireRule(aNode.getBuildTarget());
    b = ruleResolver.requireRule(bNode.getBuildTarget());
    c = ruleResolver.requireRule(cNode.getBuildTarget());
    d = ruleResolver.requireRule(dNode.getBuildTarget());
    e = ruleResolver.requireRule(eNode.getBuildTarget());
    z = ruleResolver.requireRule(zNode.getBuildTarget());
  }

  @Test
  public void getOutputClasspathEntries() throws Exception {
    JavaLibrary aLib = (JavaLibrary) a;
    assertEquals(
        ImmutableSet.of(
            getFullOutput(a),
            getFullOutput(c), // a exports c
            getFullOutput(e)  // c exports e
        ),
        JavaLibraryClasspathProvider.getOutputClasspathJars(
            aLib,
            resolver,
            Optional.of(sourcePathFunction.apply(aLib.getPathToOutput())))
    );
  }

  @Test
  public void getClasspathFromLibraries() throws Exception {
    assertEquals(
        ImmutableSet.of(
            getFullOutput(a),
            getFullOutput(c),
            getFullOutput(e)),
            // b is non-java so b and d do not appear
        JavaLibraryClasspathProvider.getClasspathsFromLibraries(
            JavaLibraryClasspathProvider.getClasspathDeps(ImmutableSet.of(a))));

    assertEquals(
        ImmutableSet.of(
            getFullOutput(c),
            getFullOutput(e), // c exports e
            getFullOutput(d)
        ),
        JavaLibraryClasspathProvider.getClasspathsFromLibraries(
            JavaLibraryClasspathProvider.getClasspathDeps(ImmutableSet.of(c, d))));
  }

  @Test
  public void getClasspathDeps() {
    assertEquals(
        ImmutableSet.of(
            a, c, e
        ),
        JavaLibraryClasspathProvider.getClasspathDeps(ImmutableSet.of(a))
    );

    assertEquals(
        ImmutableSet.of(
            d, c, e
        ),
        JavaLibraryClasspathProvider.getClasspathDeps(ImmutableSet.of(d, c))
    );
  }

  @Test
  public void getTransitiveClasspaths() throws Exception {
    JavaLibrary aLib = (JavaLibrary) a;
    assertEquals(
        ImmutableSet.builder()
            .add(getFullOutput(a))
            .add(getFullOutput(c))  // a exports c
            .add(getFullOutput(e))  // c exports e
            // b is non-java so b and d do not appear
            .build(),
        aLib.getTransitiveClasspaths());
  }

  @Test
  public void getTransitiveClasspathDeps() throws Exception {
    TargetNode<?, ?> noOutputNode = makeRule(
        "//no:output",
        ImmutableSet.of(),
        ImmutableSet.of(zNode),
        filesystem);

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(aNode, bNode, cNode, dNode, eNode, zNode, noOutputNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    JavaLibrary noOutput = (JavaLibrary) ruleResolver.requireRule(noOutputNode.getBuildTarget());

    assertEquals(
        "root does not appear if output jar not present.",
        ImmutableSet.of(
            c, e, z
        ),
        JavaLibraryClasspathProvider.getTransitiveClasspathDeps(noOutput));

    assertEquals(
        "root does appear if output jar present.",
        ImmutableSet.of(
            z, c, e
        ),
        JavaLibraryClasspathProvider.getTransitiveClasspathDeps((JavaLibrary) z));

    BuildRule mavenCoord = new JavaLibraryBuilder(
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
  public void getJavaLibraryDeps() throws Exception {
    assertThat(
        JavaLibraryClasspathProvider.getJavaLibraryDeps(ImmutableList.of(a, b, c, d, e)),
        Matchers.containsInAnyOrder(a, c, d, e)
    );
  }

  private Path getFullOutput(BuildRule lib) {
    return basePath.resolve(lib.getPathToOutput().toString());
  }

  private static TargetNode<?, ?> makeRule(
      String target,
      Iterable<String> srcs,
      Iterable<TargetNode<?, ?>> deps,
      ProjectFilesystem filesystem) throws Exception {
    return makeRule(target, srcs, deps, null, filesystem);
  }

  private static TargetNode<?, ?> makeRule(
      String target,
      Iterable<String> srcs,
      Iterable<TargetNode<?, ?>> deps,
      @Nullable Iterable<TargetNode<?, ?>> exportedDeps,
      final ProjectFilesystem filesystem) throws Exception {
    JavaLibraryBuilder builder;
    BuildTarget parsedTarget = BuildTargetFactory.newInstance(target);
    builder = JavaLibraryBuilder.createBuilder(parsedTarget);

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
