/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.project.intellij.lang.python;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidBinaryDescriptionArg;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResourceDescriptionArg;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.IjTestProjectConfig;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.python.PythonLibraryBuilder;
import com.facebook.buck.features.python.PythonLibraryDescriptionArg;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class PythonLibraryModuleRuleTest {
  private static final String TARGET = "//foo/bar/baz:target";
  private static final Path MODULE_PATH = Paths.get("foo", "bar", "baz");
  private final PythonLibraryModuleRule rule =
      new PythonLibraryModuleRule(
          new FakeProjectFilesystem(),
          new IjModuleFactoryResolver() {
            @Override
            public Optional<Path> getDummyRDotJavaPath(TargetNode<?> targetNode) {
              return Optional.empty();
            }

            @Override
            public Path getAndroidManifestPath(TargetNode<AndroidBinaryDescriptionArg> targetNode) {
              return null;
            }

            @Override
            public Optional<Path> getLibraryAndroidManifestPath(
                TargetNode<AndroidLibraryDescription.CoreArg> targetNode) {
              return Optional.empty();
            }

            @Override
            public Optional<Path> getResourceAndroidManifestPath(
                TargetNode<AndroidResourceDescriptionArg> targetNode) {
              return Optional.empty();
            }

            @Override
            public Optional<Path> getProguardConfigPath(
                TargetNode<AndroidBinaryDescriptionArg> targetNode) {
              return Optional.empty();
            }

            @Override
            public Optional<Path> getAndroidResourcePath(
                TargetNode<AndroidResourceDescriptionArg> targetNode) {
              return Optional.empty();
            }

            @Override
            public Optional<Path> getAssetsPath(
                TargetNode<AndroidResourceDescriptionArg> targetNode) {
              return Optional.empty();
            }

            @Override
            public Optional<Path> getAnnotationOutputPath(
                TargetNode<? extends JvmLibraryArg> targetNode) {
              return Optional.empty();
            }

            @Override
            public Optional<Path> getKaptAnnotationOutputPath(
                TargetNode<? extends JvmLibraryArg> targetNode) {
              return Optional.empty();
            }

            @Override
            public Optional<Path> getCompilerOutputPath(
                TargetNode<? extends JvmLibraryArg> targetNode) {
              return Optional.empty();
            }
          },
          IjTestProjectConfig.create());

  @Test
  public void adjustModulePathWithNoBaseModule() {
    TargetNode<PythonLibraryDescriptionArg> targetNode =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance(TARGET)).build();
    assertEquals(MODULE_PATH, rule.adjustModulePath(targetNode, MODULE_PATH));
  }

  @Test
  public void adjustModulePathWithEmptyBaseModule() {
    TargetNode<PythonLibraryDescriptionArg> targetNode =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance(TARGET))
            .setBaseModule("")
            .build();
    assertEquals(MODULE_PATH, rule.adjustModulePath(targetNode, MODULE_PATH));
  }

  @Test
  public void adjustModulePathWithValidBaseModule() {
    TargetNode<PythonLibraryDescriptionArg> targetNode =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance(TARGET))
            .setBaseModule("bar.baz")
            .build();
    assertEquals(Paths.get("foo"), rule.adjustModulePath(targetNode, MODULE_PATH));
  }

  @Test
  public void adjustModulePathWithInvalidBaseModule() {
    TargetNode<PythonLibraryDescriptionArg> targetNode =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance(TARGET))
            .setBaseModule("bar.baz1")
            .build();
    assertEquals(MODULE_PATH, rule.adjustModulePath(targetNode, MODULE_PATH));
  }

  @Test
  public void adjustModulePathWithNullResult() {
    TargetNode<PythonLibraryDescriptionArg> targetNode =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance(TARGET))
            .setBaseModule("foo.bar.baz")
            .build();
    assertEquals(MODULE_PATH, rule.adjustModulePath(targetNode, MODULE_PATH));
  }
}
