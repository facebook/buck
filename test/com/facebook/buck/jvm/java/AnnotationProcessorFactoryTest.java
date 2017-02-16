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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Set;

import javax.tools.ToolProvider;

public class AnnotationProcessorFactoryTest {
  @Test
  public void testAnnotationProcessorClassloadersNotReusedByDefault() throws MalformedURLException {
    assertFalse(isAnnotationProcessorClassLoaderReused(
        ImmutableList.of("some.Processor"),  // processors
        Collections.emptySet()));    // safe processors
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

    AnnotationProcessorFactory factory = new AnnotationProcessorFactory(
        null,
        baseClassLoader,
        classLoaderCache,
        safeAnnotationProcessors,
        buildTarget);

    ProcessorBundle bundle = new ProcessorBundle();
    factory.setProcessorBundleClassLoader(
        annotationProcessors,
        annotationProcessorClasspath,
        baseClassLoader,
        classLoaderCache,
        safeAnnotationProcessors,
        buildTarget,
        bundle);

    ProcessorBundle bundle2 = new ProcessorBundle();
    factory.setProcessorBundleClassLoader(
        annotationProcessors,
        annotationProcessorClasspath,
        baseClassLoader,
        classLoaderCache,
        safeAnnotationProcessors,
        buildTarget,
        bundle2);

    return bundle.classLoader == bundle2.classLoader;
  }

}
