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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavacPluginProperties.Type;
import com.facebook.buck.jvm.java.javax.SynchronizedToolProvider;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class PluginFactoryTest {

  @Test
  public void testPluginClassloadersNotReusedIfAnyMarkedUnsafe() {
    assertFalse(isPluginClassLoaderReused(false)); // safe processors
  }

  @Test
  public void testPluginClassloadersReusedIfAllMarkedSafe() {
    assertTrue(isPluginClassLoaderReused(true)); // safe processors
  }

  private boolean isPluginClassLoaderReused(boolean canReuseClasspath) {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    SourcePath controlClasspath = FakeSourcePath.of("some/path/to.jar");
    SourcePath variableClasspath = FakeSourcePath.of("some/path/to_other.jar");

    ClassLoader baseClassLoader = SynchronizedToolProvider.getSystemToolClassLoader();
    ClassLoaderCache classLoaderCache = new ClassLoaderCache();

    ResolvedJavacPluginProperties controlPluginGroup =
        new ResolvedJavacPluginProperties(
            JavacPluginProperties.builder()
                .setType(Type.JAVAC_PLUGIN)
                .addClasspathEntries(controlClasspath)
                .addProcessorNames("controlPlugin")
                .setCanReuseClassLoader(true) // control can always reuse
                .setDoesNotAffectAbi(false)
                .setSupportsAbiGenerationFromSource(false)
                .build());

    ResolvedJavacPluginProperties variablePluginGroup =
        new ResolvedJavacPluginProperties(
            JavacPluginProperties.builder()
                .setType(Type.JAVAC_PLUGIN)
                .addClasspathEntries(variableClasspath)
                .addProcessorNames("variablePlugin")
                .setCanReuseClassLoader(canReuseClasspath)
                .setDoesNotAffectAbi(false)
                .setSupportsAbiGenerationFromSource(false)
                .build());

    try (PluginFactory factory1 = new PluginFactory(baseClassLoader, classLoaderCache);
        PluginFactory factory2 = new PluginFactory(baseClassLoader, classLoaderCache)) {
      ImmutableList<JavacPluginJsr199Fields> pluginGroups =
          ImmutableList.of(
              controlPluginGroup.getJavacPluginJsr199Fields(
                  new TestActionGraphBuilder().getSourcePathResolver(), filesystem),
              variablePluginGroup.getJavacPluginJsr199Fields(
                  new TestActionGraphBuilder().getSourcePathResolver(), filesystem));
      ClassLoader classLoader1 = factory1.getClassLoaderForProcessorGroups(pluginGroups);
      ClassLoader classLoader2 = factory2.getClassLoaderForProcessorGroups(pluginGroups);
      return classLoader1 == classLoader2;
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
