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

import com.facebook.buck.android.AndroidPrebuiltAarBuilder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DefaultIjLibraryFactoryTest {

  private SourcePathResolver sourcePathResolver;
  private Path guavaJarPath;
  private TargetNode<?> guava;
  private IjLibrary guavaLibrary;
  private TargetNode<?> androidSupport;
  private Path androidSupportBinaryPath;
  private IjLibrary androidSupportLibrary;
  private IjLibrary baseLibrary;
  private TargetNode<?> base;
  private Path baseOutputPath;
  private DefaultIjLibraryFactory.IjLibraryFactoryResolver libraryFactoryResolver;
  private IjLibraryFactory factory;

  @Before
  public void setUp() throws Exception {
    sourcePathResolver = new SourcePathResolver(new BuildRuleResolver());
    guavaJarPath = Paths.get("third_party/java/guava.jar");
    guava = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
        .setBinaryJar(guavaJarPath)
        .build();

    androidSupportBinaryPath = Paths.get("third_party/java/support/support.aar");
    androidSupport = AndroidPrebuiltAarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/support:support"))
        .setBinaryAar(androidSupportBinaryPath)
        .build();

    base = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guava.getBuildTarget())
        .build();

    baseOutputPath = Paths.get("buck-out/base.jar");

    libraryFactoryResolver = new DefaultIjLibraryFactory.IjLibraryFactoryResolver() {
      @Override
      public Path getPath(SourcePath path) {
        return sourcePathResolver.getPath(path);
      }

      @Override
      public Optional<Path> getPathIfJavaLibrary(TargetNode<?> targetNode) {
        if (targetNode.equals(base)) {
          return Optional.of(baseOutputPath);
        }
        return Optional.absent();
      }
    };

    factory = new DefaultIjLibraryFactory(libraryFactoryResolver);

    guavaLibrary = factory.getLibrary(guava).get();
    androidSupportLibrary = factory.getLibrary(androidSupport).get();
    baseLibrary = factory.getLibrary(base).get();
  }

  @Test
  public void testPrebuiltJar() {
    assertEquals("library_third_party_java_guava_guava", guavaLibrary.getName());
    assertEquals(Optional.of(guavaJarPath), guavaLibrary.getBinaryJar());
    assertEquals(ImmutableSet.of(guava), guavaLibrary.getTargets());
  }

  @Test
  public void testPrebuiltAar() {
    assertEquals("library_third_party_java_support_support", androidSupportLibrary.getName());
    assertEquals(Optional.of(androidSupportBinaryPath), androidSupportLibrary.getBinaryJar());
    assertEquals(ImmutableSet.of(androidSupport), androidSupportLibrary.getTargets());
  }

  @Test
  public void testLibraryFromOtherTargets() {
    assertEquals("library_java_com_example_base_base", baseLibrary.getName());
    assertEquals(Optional.of(baseOutputPath), baseLibrary.getBinaryJar());
    assertEquals(ImmutableSet.of(base), baseLibrary.getTargets());
  }
}
