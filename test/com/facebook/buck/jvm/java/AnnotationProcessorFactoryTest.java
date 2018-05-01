/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ClassLoaderCache;
import java.io.IOException;
import java.net.MalformedURLException;
import javax.tools.ToolProvider;
import org.junit.Test;

public class AnnotationProcessorFactoryTest {
  @Test
  public void testAnnotationProcessorClassloadersNotReusedIfMarkedUnsafe()
      throws MalformedURLException {
    assertFalse(
        isAnnotationProcessorClassLoaderReused(
            "some.Processor", // processor
            false)); // safe processors
  }

  @Test
  public void testAnnotationProcessorClassloadersReusedIfMarkedSafe() throws MalformedURLException {
    assertTrue(
        isAnnotationProcessorClassLoaderReused(
            "some.Processor", // processor
            true)); // safe processors
  }

  private boolean isAnnotationProcessorClassLoaderReused(
      String annotationProcessor, boolean canReuseClasspath) {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePath classpath = FakeSourcePath.of("some/path/to.jar");
    ClassLoader baseClassLoader = ToolProvider.getSystemToolClassLoader();
    ClassLoaderCache classLoaderCache = new ClassLoaderCache();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:test");
    ResolvedJavacPluginProperties processorGroup =
        new ResolvedJavacPluginProperties(
            JavacPluginProperties.builder()
                .addClasspathEntries(classpath)
                .addProcessorNames(annotationProcessor)
                .setCanReuseClassLoader(canReuseClasspath)
                .setDoesNotAffectAbi(false)
                .setSupportsAbiGenerationFromSource(false)
                .build(),
            filesystem,
            DefaultSourcePathResolver.from(null));

    try (AnnotationProcessorFactory factory1 =
            new AnnotationProcessorFactory(null, baseClassLoader, classLoaderCache, buildTarget);
        AnnotationProcessorFactory factory2 =
            new AnnotationProcessorFactory(null, baseClassLoader, classLoaderCache, buildTarget)) {
      JavacPluginJsr199Fields fields = processorGroup.getJavacPluginJsr199Fields();
      ClassLoader classLoader1 = factory1.getClassLoaderForProcessorGroup(fields);
      ClassLoader classLoader2 = factory2.getClassLoaderForProcessorGroup(fields);
      return classLoader1 == classLoader2;
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
