/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.jvm.java.JavaBuckConfig.TARGETED_JAVA_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

import javax.tools.ToolProvider;

public class Jsr199JavacTest extends EasyMockSupport {
  private static final Path PATH_TO_SRCS_LIST = Paths.get("srcs_list");
  public static final ImmutableSortedSet<Path> SOURCE_FILES =
      ImmutableSortedSet.of(Paths.get("foobar.java"));

  @Test
  public void testJavacCommand() {
    Jsr199Javac firstOrder = createTestStep();
    Jsr199Javac warn = createTestStep();
    Jsr199Javac transitive = createTestStep();

    assertEquals(
        String.format("javac -source %s -target %s -g -d . -classpath foo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, PATH_TO_SRCS_LIST),
        firstOrder.getDescription(
            getArgs().add("foo.jar").build(),
            SOURCE_FILES,
            PATH_TO_SRCS_LIST));
    assertEquals(
        String.format("javac -source %s -target %s -g -d . -classpath foo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, PATH_TO_SRCS_LIST),
        warn.getDescription(
            getArgs().add("foo.jar").build(),
            SOURCE_FILES,
            PATH_TO_SRCS_LIST));
    assertEquals(
        String.format("javac -source %s -target %s -g -d . -classpath bar.jar%sfoo.jar @%s",
            TARGETED_JAVA_VERSION, TARGETED_JAVA_VERSION, File.pathSeparator, PATH_TO_SRCS_LIST),
        transitive.getDescription(
            getArgs().add("bar.jar" + File.pathSeparator + "foo.jar").build(),
            SOURCE_FILES,
            PATH_TO_SRCS_LIST));
  }

  @Test
  public void testAnnotationProcessorClassloadersNotReusedByDefault() throws MalformedURLException {
    assertFalse(isAnnotationProcessorClassLoaderReused(
        ImmutableList.of("some.Processor"),  // processors
        Collections.<String>emptySet()));    // safe processors
  }

  @Test
  public void testAnnotationProcessorClassloadersReusedIfMarkedSafe() throws MalformedURLException {
    assertTrue(isAnnotationProcessorClassLoaderReused(
        ImmutableList.of("some.Processor"),  // processors
        ImmutableSet.of("some.Processor")));    // safe processors
  }

  @Test
  public void testAnnotationProcessorsMustAllBeSafeToReuseClassLoader()
      throws MalformedURLException {
    assertFalse(isAnnotationProcessorClassLoaderReused(
        ImmutableList.of("some.Processor", "some.other.Processor"),  // processors
        ImmutableSet.of("some.Processor")));    // safe processors
  }

  private boolean isAnnotationProcessorClassLoaderReused(
      ImmutableList<String> annotationProcessors,
      Set<String> safeAnnotationProcessors) throws MalformedURLException {
    URL[] annotationProcessorClasspath = {new URL("file:///some/path/to.jar")};
    ClassLoader baseClassLoader = ToolProvider.getSystemToolClassLoader();
    ClassLoaderCache classLoaderCache = new ClassLoaderCache();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:test");

    Jsr199Javac step = createTestStep();
    Jsr199Javac.ProcessorBundle bundle = new Jsr199Javac.ProcessorBundle();
    step.setProcessorBundleClassLoader(
        annotationProcessors,
        annotationProcessorClasspath,
        baseClassLoader,
        classLoaderCache,
        safeAnnotationProcessors,
        buildTarget,
        bundle);

    Jsr199Javac.ProcessorBundle bundle2 = new Jsr199Javac.ProcessorBundle();
    step.setProcessorBundleClassLoader(
        annotationProcessors,
        annotationProcessorClasspath,
        baseClassLoader,
        classLoaderCache,
        safeAnnotationProcessors,
        buildTarget,
        bundle2);

    return bundle.classLoader == bundle2.classLoader;
  }

  private Jsr199Javac createTestStep() {
    return new JdkProvidedInMemoryJavac();
  }

  private ImmutableList.Builder<String> getArgs() {
    return ImmutableList.<String>builder().add(
        "-source", TARGETED_JAVA_VERSION,
        "-target", TARGETED_JAVA_VERSION,
        "-g",
        "-d", ".",
        "-classpath");
  }
}
