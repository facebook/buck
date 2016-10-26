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

import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class JarShapeTest {

  private BuildRuleResolver resolver;

  @Before
  public void createRuleResolver() {
    resolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());
  }

  @Test
  public void shouldOnlyIncludeGivenJarInASingleJar() throws NoSuchBuildTargetException {
    BuildRule dep = JavaLibraryBuilder.createBuilder("//:dep")
        .addSrc(new FakeSourcePath("SomeFile.java"))
        .build(resolver);

    BuildRule lib = JavaLibraryBuilder.createBuilder("//:lib")
        .addSrc(new FakeSourcePath("Library.java"))
        .addDep(dep.getBuildTarget())
        .build(resolver);

    JarShape.Summary deps = JarShape.SINGLE.gatherDeps(lib);

    assertEquals(ImmutableSortedSet.of(lib), deps.getPackagedRules());
    assertEquals(ImmutableSet.of(dep, lib), deps.getClasspath());
    assertTrue(deps.getMavenDeps().isEmpty());
  }

  @Test
  public void shouldIncludeEverythingInAnUberJar() throws NoSuchBuildTargetException {
    BuildRule dep = JavaLibraryBuilder.createBuilder("//:dep")
        .addSrc(new FakeSourcePath("SomeFile.java"))
        .build(resolver);

    BuildRule lib = JavaLibraryBuilder.createBuilder("//:lib")
        .addSrc(new FakeSourcePath("Library.java"))
        .addDep(dep.getBuildTarget())
        .build(resolver);

    JarShape.Summary deps = JarShape.UBER.gatherDeps(lib);

    assertEquals(ImmutableSortedSet.of(dep, lib), deps.getPackagedRules());
    assertEquals(ImmutableSet.of(dep, lib), deps.getClasspath());
    assertTrue(deps.getMavenDeps().isEmpty());
  }

  @Test
  public void aMavenJarWithoutMavenTransitiveDepsIsAnUberJar() throws NoSuchBuildTargetException {
    BuildRule dep = JavaLibraryBuilder.createBuilder("//:dep")
        .addSrc(new FakeSourcePath("SomeFile.java"))
        .build(resolver);

    BuildRule lib = JavaLibraryBuilder.createBuilder("//:lib")
        .addSrc(new FakeSourcePath("Library.java"))
        .addDep(dep.getBuildTarget())
        .build(resolver);

    JarShape.Summary deps = JarShape.MAVEN.gatherDeps(lib);

    assertEquals(ImmutableSortedSet.of(dep, lib), deps.getPackagedRules());
    assertEquals(ImmutableSet.of(dep, lib), deps.getClasspath());
    assertTrue(deps.getMavenDeps().isEmpty());
  }

  @Test
  public void shouldBeAbleToCreateAMavenJar() throws NoSuchBuildTargetException {
    BuildRule dep = JavaLibraryBuilder.createBuilder("//:dep")
        .addSrc(new FakeSourcePath("SomeFile.java"))
        .build(resolver);

    BuildRule mavenDep = JavaLibraryBuilder.createBuilder("//:maven-dep")
        .addSrc(new FakeSourcePath("SomeFile.java"))
        .setMavenCoords("com.example:somelib:1.0")
        .build(resolver);

    BuildRule lib = JavaLibraryBuilder.createBuilder("//:lib")
        .addSrc(new FakeSourcePath("Library.java"))
        .addDep(dep.getBuildTarget())
        .addDep(mavenDep.getBuildTarget())
        .build(resolver);

    JarShape.Summary deps = JarShape.MAVEN.gatherDeps(lib);

    assertEquals(ImmutableSortedSet.of(dep, lib), deps.getPackagedRules());
    assertEquals(ImmutableSet.of(dep, lib, mavenDep), deps.getClasspath());
    assertEquals(ImmutableSet.of(mavenDep), deps.getMavenDeps());
  }

  @Test
  public void shouldCorrectlyExcludeMavenDepsWhichAreDepsOfSelf() throws NoSuchBuildTargetException {
    BuildRule deepMavenDep = JavaLibraryBuilder.createBuilder("//:deep-maven")
        .addSrc(new FakeSourcePath("SomeFile.java"))
        .setMavenCoords("com.example:cheese:2.0")
        .build(resolver);

    BuildRule mavenDep = JavaLibraryBuilder.createBuilder("//:maven-dep")
        .addSrc(new FakeSourcePath("SomeFile.java"))
        .setMavenCoords("com.example:somelib:1.0")
        .addDep(deepMavenDep.getBuildTarget())
        .build(resolver);

    BuildRule lib = JavaLibraryBuilder.createBuilder("//:lib")
        .addSrc(new FakeSourcePath("Library.java"))
        .addDep(deepMavenDep.getBuildTarget())
        .addDep(mavenDep.getBuildTarget())
        .build(resolver);

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
