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

package com.facebook.buck.ide.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidPrebuiltAarBuilder;
import com.facebook.buck.ide.intellij.model.IjLibrary;
import com.facebook.buck.ide.intellij.model.IjLibraryFactory;
import com.facebook.buck.ide.intellij.model.IjLibraryFactoryResolver;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class DefaultIjLibraryFactoryTest {

  private SourcePathResolver sourcePathResolver;
  private Path guavaJarPath;
  private TargetNode<?, ?> guava;
  private IjLibrary guavaLibrary;
  private TargetNode<?, ?> androidSupport;
  private PathSourcePath androidSupportBinaryPath;
  private PathSourcePath androidSupportResClassPath;
  private IjLibrary androidSupportLibrary;
  private IjLibrary baseLibrary;
  private TargetNode<?, ?> base;
  private PathSourcePath androidSupportBinaryJarPath;
  private PathSourcePath baseOutputPath;
  private IjLibraryFactoryResolver libraryFactoryResolver;
  private IjLibraryFactory factory;

  @Before
  public void setUp() throws Exception {
    sourcePathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestBuildRuleResolver()));
    guavaJarPath = Paths.get("third_party/java/guava.jar");
    guava =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
            .setBinaryJar(guavaJarPath)
            .build();

    androidSupportBinaryPath = FakeSourcePath.of("third_party/java/support/support.aar");
    androidSupport =
        AndroidPrebuiltAarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/java/support:support"))
            .setBinaryAar(androidSupportBinaryPath)
            .build();

    base =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guava.getBuildTarget())
            .build();

    androidSupportBinaryJarPath = FakeSourcePath.of("buck_out/support.aar/classes.jar");
    androidSupportResClassPath =
        FakeSourcePath.of("buck-out/bin/third_party/java/support/__unpack_support#aar_unzip__/res");
    baseOutputPath = FakeSourcePath.of("buck-out/base.jar");

    libraryFactoryResolver =
        new IjLibraryFactoryResolver() {
          @Override
          public Path getPath(SourcePath path) {
            return sourcePathResolver.getRelativePath(path);
          }

          @Override
          public Optional<SourcePath> getPathIfJavaLibrary(TargetNode<?, ?> targetNode) {
            if (targetNode.equals(base)) {
              return Optional.of(baseOutputPath);
            }
            if (targetNode.equals(androidSupport)) {
              return Optional.of(androidSupportBinaryJarPath);
            }
            return Optional.empty();
          }
        };

    factory = new DefaultIjLibraryFactory(libraryFactoryResolver);

    guavaLibrary = factory.getLibrary(guava).get();
    androidSupportLibrary = factory.getLibrary(androidSupport).get();
    baseLibrary = factory.getLibrary(base).get();
  }

  @Test
  public void testPrebuiltJar() {
    assertEquals("//third_party/java/guava:guava", guavaLibrary.getName());
    assertEquals(ImmutableSet.of(guavaJarPath), guavaLibrary.getBinaryJars());
    assertEquals(ImmutableSet.of(guava.getBuildTarget()), guavaLibrary.getTargets());
  }

  @Test
  public void testPrebuiltAar() {
    assertEquals("//third_party/java/support:support", androidSupportLibrary.getName());
    assertEquals(
        ImmutableSet.of(androidSupportBinaryJarPath.getRelativePath()),
        androidSupportLibrary.getBinaryJars());
    assertEquals(
        ImmutableSet.of(androidSupport.getBuildTarget()), androidSupportLibrary.getTargets());
    assertEquals(
        ImmutableSet.of(androidSupportResClassPath.getRelativePath()),
        androidSupportLibrary.getClassPaths());
  }

  @Test
  public void testLibraryFromOtherTargets() {
    assertEquals("//java/com/example/base:base", baseLibrary.getName());
    assertEquals(ImmutableSet.of(baseOutputPath.getRelativePath()), baseLibrary.getBinaryJars());
    assertEquals(ImmutableSet.of(base.getBuildTarget()), baseLibrary.getTargets());
  }
}
