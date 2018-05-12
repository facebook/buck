/*
 * Copyright 2013-present Facebook, Inc.
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Ignore;
import org.junit.Test;

public class JarShapeTest {

  @Test
  public void shouldOnlyIncludeGivenJarInASingleJar() throws NoSuchBuildTargetException {
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder("//:dep")
            .addSrc(FakeSourcePath.of("SomeFile.java"))
            .build();

    TargetNode<?, ?> libNode =
        JavaLibraryBuilder.createBuilder("//:lib")
            .addSrc(FakeSourcePath.of("Library.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, libNode);
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);

    BuildRule dep = resolver.requireRule(depNode.getBuildTarget());
    BuildRule lib = resolver.requireRule(libNode.getBuildTarget());

    JarShape.Summary deps = JarShape.SINGLE.gatherDeps(lib);

    assertEquals(ImmutableSortedSet.of(lib), deps.getPackagedRules());
    assertEquals(ImmutableSet.of(dep, lib), deps.getClasspath());
    assertTrue(deps.getMavenDeps().isEmpty());
  }

  @Test
  public void aMavenJarWithoutMavenTransitiveDepsIsAnUberJar() throws NoSuchBuildTargetException {
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder("//:dep")
            .addSrc(FakeSourcePath.of("SomeFile.java"))
            .build();

    TargetNode<?, ?> libNode =
        JavaLibraryBuilder.createBuilder("//:lib")
            .addSrc(FakeSourcePath.of("Library.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, libNode);
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);

    BuildRule dep = resolver.requireRule(depNode.getBuildTarget());
    BuildRule lib = resolver.requireRule(libNode.getBuildTarget());

    JarShape.Summary deps = JarShape.MAVEN.gatherDeps(lib);

    assertEquals(ImmutableSortedSet.of(dep, lib), deps.getPackagedRules());
    assertEquals(ImmutableSet.of(dep, lib), deps.getClasspath());
    assertTrue(deps.getMavenDeps().isEmpty());
  }

  @Test
  public void shouldBeAbleToCreateAMavenJar() throws NoSuchBuildTargetException {
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder("//:dep")
            .addSrc(FakeSourcePath.of("SomeFile.java"))
            .build();

    TargetNode<?, ?> mavenDepNode =
        JavaLibraryBuilder.createBuilder("//:maven-dep")
            .addSrc(FakeSourcePath.of("SomeFile.java"))
            .setMavenCoords("com.example:somelib:1.0")
            .build();

    TargetNode<?, ?> libNode =
        JavaLibraryBuilder.createBuilder("//:lib")
            .addSrc(FakeSourcePath.of("Library.java"))
            .addDep(depNode.getBuildTarget())
            .addDep(mavenDepNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, mavenDepNode, libNode);
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);

    BuildRule dep = resolver.requireRule(depNode.getBuildTarget());
    BuildRule mavenDep = resolver.requireRule(mavenDepNode.getBuildTarget());
    BuildRule lib = resolver.requireRule(libNode.getBuildTarget());

    JarShape.Summary deps = JarShape.MAVEN.gatherDeps(lib);

    assertEquals(ImmutableSortedSet.of(dep, lib), deps.getPackagedRules());
    assertEquals(ImmutableSet.of(dep, lib, mavenDep), deps.getClasspath());
    assertEquals(ImmutableSet.of(mavenDep), deps.getMavenDeps());
  }

  @Test
  public void shouldCorrectlyExcludeMavenDepsWhichAreDepsOfSelf()
      throws NoSuchBuildTargetException {
    TargetNode<?, ?> deepMavenDepNode =
        JavaLibraryBuilder.createBuilder("//:deep-maven")
            .addSrc(FakeSourcePath.of("SomeFile.java"))
            .setMavenCoords("com.example:cheese:2.0")
            .build();

    TargetNode<?, ?> mavenDepNode =
        JavaLibraryBuilder.createBuilder("//:maven-dep")
            .addSrc(FakeSourcePath.of("SomeFile.java"))
            .setMavenCoords("com.example:somelib:1.0")
            .addDep(deepMavenDepNode.getBuildTarget())
            .build();

    TargetNode<?, ?> libNode =
        JavaLibraryBuilder.createBuilder("//:lib")
            .addSrc(FakeSourcePath.of("Library.java"))
            .addDep(deepMavenDepNode.getBuildTarget())
            .addDep(mavenDepNode.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(deepMavenDepNode, mavenDepNode, libNode);
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);

    BuildRule deepMavenDep = resolver.requireRule(deepMavenDepNode.getBuildTarget());
    BuildRule mavenDep = resolver.requireRule(mavenDepNode.getBuildTarget());
    BuildRule lib = resolver.requireRule(libNode.getBuildTarget());

    JarShape.Summary deps = JarShape.MAVEN.gatherDeps(lib);

    assertEquals(ImmutableSortedSet.of(lib), deps.getPackagedRules());
    assertEquals(ImmutableSet.of(deepMavenDep, lib, mavenDep), deps.getClasspath());
    assertEquals(ImmutableSet.of(mavenDep), deps.getMavenDeps());
  }

  @Test
  @Ignore("Current implementations get this wrong")
  public void shouldNeverPackageProvidedDepsInAnUberJar() {
    fail("Not written yet");
  }

  @Test
  @Ignore("Current implementations get this wrong")
  public void shouldNeverPackageProvidedDepsInAMavenJar() {
    fail("Not written yet");
  }
}
