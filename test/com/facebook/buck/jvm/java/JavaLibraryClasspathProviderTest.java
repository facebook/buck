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
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
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
  private BuildRuleResolver ruleResolver;

  @Before
  public void setUp() throws Exception {
    filesystem = new FakeProjectFilesystem();
    ruleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());
    resolver = new SourcePathResolver(ruleResolver);
    basePath = filesystem.getRootPath();

    // Create our target graph. All nodes are JavaLibrary except b
    // (exports c) az (no exports)
    //            /  \
    //(non java) b    c (exports e)
    //           |    |
    //           d    e
    d = makeRule("//foo:d",
        ImmutableSet.of("foo", "d.java"),
        ImmutableSet.of(),
        ruleResolver,
        filesystem);

    sourcePathFunction = SourcePaths.toSourcePath(filesystem);
    b = GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//foo:b"))
        .setSrcs(ImmutableList.of(
            sourcePathFunction.apply(Paths.get("foo", "b.java"))))
        .setCmd("echo $(classpath //foo:d")
        .setOut("b.out")
        .build(ruleResolver);

    e = makeRule("//foo:e",
        ImmutableSet.of("foo", "e.java"),
        ImmutableSet.of(),
        ruleResolver,
        filesystem);

    // exported
    c = makeRule("//foo:c",
        ImmutableSet.of("foo", "c.java"),
        ImmutableSet.of(e),
        ImmutableSet.of(e),  // exported
        ruleResolver,
        filesystem);

    a = makeRule("//foo:a",
        ImmutableSet.of("foo", "a.java"),
        ImmutableSet.of(b, c),
        ImmutableSet.of(c),
        ruleResolver,
        filesystem);

    z = makeRule("//foo:z",
        ImmutableSet.of("foo", "a.java"),
        ImmutableSet.of(b, c),
        ruleResolver,
        filesystem);
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
    JavaLibrary noOutput = makeRule(
        "//no:output",
        ImmutableSet.of(),
        ImmutableSet.of(z),
        ruleResolver,
        filesystem);

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

  private static JavaLibrary makeRule(
      String target,
      Iterable<String> srcs,
      Iterable<BuildRule> deps,
      BuildRuleResolver ruleResolver,
      ProjectFilesystem filesystem) throws Exception {
    return makeRule(target, srcs, deps, null, ruleResolver, filesystem);
  }

  private static JavaLibrary makeRule(
      String target,
      Iterable<String> srcs,
      Iterable<BuildRule> deps,
      @Nullable Iterable<BuildRule> exportedDeps,
      BuildRuleResolver ruleResolver,
      final ProjectFilesystem filesystem) throws Exception {
    JavaLibraryBuilder builder;
    BuildTarget parsedTarget = BuildTargetFactory.newInstance(target);
    builder = JavaLibraryBuilder.createBuilder(parsedTarget);

    for (String src : srcs) {
      builder.addSrc(filesystem.getBuckPaths().getGenDir().resolve(src));
    }
    for (BuildRule dep : deps) {
      builder.addDep(dep.getBuildTarget());
    }

    if (exportedDeps != null) {
      for (BuildRule dep : exportedDeps) {
        builder.addExportedDep(dep.getBuildTarget());
      }
    }
    return (JavaLibrary) ruleResolver.addToIndex(builder.build(ruleResolver));
  }
}
