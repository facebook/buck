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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.Processor;

class AnnotationProcessorFactory {
  private static final Logger LOG = Logger.get(AnnotationProcessorFactory.class);

  private final JavacEventSink eventSink;
  private final ClassLoader compilerClassLoader;
  private final ClassLoaderCache globalClassLoaderCache;
  private final Set<String> safeAnnotationProcessors;
  private final BuildTarget target;

  AnnotationProcessorFactory(
      JavacEventSink eventSink,
      ClassLoader compilerClassLoader,
      ClassLoaderCache globalClassLoaderCache,
      Set<String> safeAnnotationProcessors,
      BuildTarget target) {
    this.eventSink = eventSink;
    this.compilerClassLoader = compilerClassLoader;
    this.globalClassLoaderCache = globalClassLoaderCache;
    this.safeAnnotationProcessors = safeAnnotationProcessors;
    this.target = target;
  }

  public ProcessorBundle createProcessors(List<String> names, URL[] urls) {
    ProcessorBundle processorBundle = new ProcessorBundle();
    setProcessorBundleClassLoader(
        names,
        urls,
        compilerClassLoader,
        globalClassLoaderCache,
        safeAnnotationProcessors,
        target,
        processorBundle);


    for (String name : names) {
      try {
        LOG.debug("Loading %s from own classloader", name);

        Class<? extends Processor> aClass =
            Preconditions.checkNotNull(processorBundle.classLoader)
                .loadClass(name)
                .asSubclass(Processor.class);
        processorBundle.processors.add(
            new TracingProcessorWrapper(
                eventSink,
                target,
                aClass.newInstance()));
      } catch (ReflectiveOperationException e) {
        // If this happens, then the build is really in trouble. Better warn the user.
        throw new HumanReadableException(
            "%s: javac unable to load annotation processor: %s",
            target.getFullyQualifiedName(),
            name);
      }
    }

    return processorBundle;
  }

  @VisibleForTesting
  void setProcessorBundleClassLoader(
      List<String> processorNames,
      URL[] processorClasspath,
      ClassLoader baseClassLoader,
      ClassLoaderCache classLoaderCache,
      Set<String> safeAnnotationProcessors,
      BuildTarget target,
      ProcessorBundle processorBundle) {
    // We can avoid lots of overhead in large builds by reusing the same classloader for annotation
    // processors. However, some annotation processors use static variables in a way that assumes
    // there is only one instance running in the process at a time (or at all), and such annotation
    // processors would break running inside of Buck. So we default to creating a new ClassLoader
    // for each build rule, with an option to whitelist "safe" processors in .buckconfig.
    if (safeAnnotationProcessors.containsAll(processorNames)) {
      LOG.debug("Reusing class loaders for %s.", target);
      processorBundle.classLoader = (URLClassLoader) classLoaderCache.getClassLoaderForClassPath(
          baseClassLoader,
          ImmutableList.copyOf(processorClasspath));
    } else {
      final List<String> unsafeProcessors = new ArrayList<>();
      for (String name : processorNames) {
        if (safeAnnotationProcessors.contains(name)) {
          continue;
        }
        unsafeProcessors.add(name);
      }
      LOG.debug(
          "Creating new class loader for %s because the following processors are not marked safe " +
              "for multiple use in a single process: %s",
          target,
          Joiner.on(',').join(unsafeProcessors));
      processorBundle.classLoader = new URLClassLoader(
          processorClasspath,
          baseClassLoader);
    }
  }
}
