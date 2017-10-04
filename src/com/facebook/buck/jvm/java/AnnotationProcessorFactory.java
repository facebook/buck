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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;

class AnnotationProcessorFactory implements AutoCloseable {
  private final JavacEventSink eventSink;
  private final ClassLoader compilerClassLoader;
  private final ClassLoaderCache globalClassLoaderCache;
  private final ClassLoaderCache localClassLoaderCache = new ClassLoaderCache();
  private final BuildTarget target;

  AnnotationProcessorFactory(
      JavacEventSink eventSink,
      ClassLoader compilerClassLoader,
      ClassLoaderCache globalClassLoaderCache,
      BuildTarget target) {
    this.eventSink = eventSink;
    this.compilerClassLoader = compilerClassLoader;
    this.globalClassLoaderCache = globalClassLoaderCache;
    this.target = target;
  }

  @Override
  public void close() throws IOException {
    localClassLoaderCache.close();
  }

  public List<Processor> createProcessors(ImmutableList<JavacPluginJsr199Fields> fields) {
    return fields
        .stream()
        .map(this::createProcessorsWithCommonClasspath)
        .flatMap(Function.identity())
        .collect(Collectors.toList());
  }

  private Stream<Processor> createProcessorsWithCommonClasspath(JavacPluginJsr199Fields fields) {
    ClassLoader classLoader = getClassLoaderForProcessorGroup(fields);
    return fields.getProcessorNames().stream().map(name -> createProcessor(classLoader, name));
  }

  private Processor createProcessor(ClassLoader classLoader, String name) {
    try {
      Class<? extends Processor> aClass = classLoader.loadClass(name).asSubclass(Processor.class);
      return new TracingProcessorWrapper(eventSink, target, aClass.newInstance());
    } catch (ReflectiveOperationException e) {
      // If this happens, then the build is really in trouble. Better warn the user.
      throw new HumanReadableException(
          e,
          "%s: javac unable to load annotation processor: %s",
          target.getFullyQualifiedName(),
          name);
    }
  }

  @VisibleForTesting
  ClassLoader getClassLoaderForProcessorGroup(JavacPluginJsr199Fields processorGroup) {
    ClassLoaderCache cache;
    // We can avoid lots of overhead in large builds by reusing the same classloader for annotation
    // processors. However, some annotation processors use static variables in a way that assumes
    // there is only one instance running in the process at a time (or at all), and such annotation
    // processors would break running inside of Buck. So we default to creating a new ClassLoader
    // for each build rule, with an option to whitelist "safe" processors in .buckconfig.
    if (processorGroup.getCanReuseClassLoader()) {
      cache = globalClassLoaderCache;
    } else {
      cache = localClassLoaderCache;
    }
    return cache.getClassLoaderForClassPath(
        compilerClassLoader, ImmutableList.copyOf(processorGroup.getClasspath()));
  }
}
