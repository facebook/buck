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

package com.facebook.buck.java.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidPrebuiltAarBuilder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IjLibraryFactoryTest {

  private SourcePathResolver sourcePathResolver;
  private Path guavaJarPath;
  private TargetNode<?> guava;
  private IjLibrary guavaLibrary;
  private TargetNode<?> androidSupport;
  private IjLibrary androidSupportLibrary;
  private TargetNode<?> base;
  private TargetNode<?> droid;
  private IjLibraryFactory factory;

  @Before
  public void setUp() throws Exception {
    sourcePathResolver = new SourcePathResolver(new BuildRuleResolver());
    guavaJarPath = Paths.get("third_party/java/guava.jar");
    guava = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
        .setBinaryJar(guavaJarPath)
        .build();

    androidSupport = AndroidPrebuiltAarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/support:support"))
        .setBinaryAar(Paths.get("third_party/java/support/support.aar"))
        .build();

    base = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guava.getBuildTarget())
        .build();

    droid = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/droid:droid"))
        .addDep(guava.getBuildTarget())
        .addDep(androidSupport.getBuildTarget())
        .build();

    factory = IjLibraryFactory.create(
        ImmutableSet.of(
            guava,
            androidSupport,
            base,
            droid
        ));

    guavaLibrary = factory.getLibrary(guava.getBuildTarget()).get();
    androidSupportLibrary = factory.getLibrary(androidSupport.getBuildTarget()).get();
  }

  @Test
  public void testPrebuiltJar() {
    assertEquals("library_third_party_java_guava_guava", guavaLibrary.getName());
    assertEquals(
        Optional.of(guavaJarPath),
        sourcePathResolver.getRelativePath(guavaLibrary.getBinaryJar()));
  }

  @Test
  public void testPrebuiltAar() {
    IjLibrary androidSupportLibrary = factory.getLibrary(androidSupport.getBuildTarget()).get();
    assertEquals("library_third_party_java_support_support", androidSupportLibrary.getName());
  }

  @Test
  public void testLibrariesDoNotDependOnThemselves() {
    assertTrue(factory.getLibraries(ImmutableSet.<TargetNode<?>>of(guava)).isEmpty());
    assertTrue(factory.getLibraries(ImmutableSet.<TargetNode<?>>of(androidSupport)).isEmpty());
  }

  @Test
  public void testGetLibraries() {
    assertEquals(
        ImmutableSet.of(guavaLibrary),
        factory.getLibraries(ImmutableSet.<TargetNode<?>>of(base)));

    assertEquals(
        ImmutableSet.of(androidSupportLibrary, guavaLibrary),
        factory.getLibraries(ImmutableSet.<TargetNode<?>>of(droid)));
  }

  @Test
  public void testGetLibrariesOfLibraries() {
    assertEquals(
        ImmutableSet.of(),
        factory.getLibraries(ImmutableSet.<TargetNode<?>>of(androidSupport)));
  }

  @Test
  public void testGetLibrariesNotTransitive() {
    TargetNode<JavaLibraryDescription.Arg> javaLib = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/droid:droid"))
        .addDep(base.getBuildTarget())
        .addDep(droid.getBuildTarget())
        .build();

    assertEquals(
        ImmutableSet.of(),
        factory.getLibraries(ImmutableSet.<TargetNode<?>>of(javaLib)));
  }

  @Test
  public void testMultipleLibrariesInSameBasePath() {
    TargetNode<?> guava = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java:guava"))
        .setBinaryJar(guavaJarPath)
        .build();

    TargetNode<?> hamcrest = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java:hamcrest"))
        .setBinaryJar(guavaJarPath)
        .build();

    IjLibraryFactory factory = IjLibraryFactory.create(ImmutableSet.of(guava, hamcrest));

    IjLibrary guavaLibrary = factory.getLibrary(guava.getBuildTarget()).get();
    IjLibrary hamcrestLibrary = factory.getLibrary(hamcrest.getBuildTarget()).get();

    assertNotEquals(guavaLibrary.getName(), hamcrestLibrary.getName());
  }
}
