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

package com.facebook.buck.java.tracing;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;

import javax.annotation.Nullable;
import javax.tools.JavaCompiler;

/**
 * A {@link JavacPhaseTracer} that translates the trace data to be more useful.
 * <p>
 * The phases of compilation are described
 * <a href="http://openjdk.java.net/groups/compiler/doc/compilation-overview/index.html">here</a>.
 * The doc describes annotation processing as conceptually occuring before compilation, but
 * actually occurring somewhat out-of-phase with the conceptual model.
 * <p>
 * Javac calls {@link TracingTaskListener} according to the conceptual model described in that
 * document: annotation processing starts at the very beginning of the run, and ends after the
 * last annotation processor is run in the last round of processing. Then there is one last parse
 * and enter before going into analyze and generate. This is problematic from a performance
 * perspective, because some of the work attributed to annotation processing would have happened
 * regardless.
 * <p>
 * This class translates the tracing data from the conceptual model back into something that more
 * closely matches the actual implementation:
 * <ul>
 *   <li>Parse, enter, analyze, and generate phases pass thru unchanged</li>
 *   <li>What javac traces as an annotation processing round is renamed
 *   "run annotation processors"</li>
 *   <li>Annotation processing rounds are traced from the beginning of "run annotation processors"
 *   to the beginning of the next "run annotation processors" or (for the last round)
 *   the first analyze phase</li>
 *   <li>Annotation processing is traced from the beginning of the first round to
 *   the end of the last</li>
 * </ul>
 * In this way, the time attributed to annotation processing is always time
 * that would not have been spent if annotation processors were not present.
 */
public class TranslatingJavacPhaseTracer implements JavacPhaseTracer {
  private static final Logger LOG = Logger.get(TranslatingJavacPhaseTracer.class);
  private static final String JAVAC_TRACING_JAR_RESOURCE_PATH = "javac-tracing-compiler-plugin.jar";
  @Nullable
  private static final URL JAVAC_TRACING_JAR_URL = extractJavaTracingJar();

  private final JavacPhaseEventLogger logger;

  private boolean isProcessingAnnotations = false;
  private int roundNumber = 0;

  /**
   * Extracts the jar containing {@link TracingTaskListener} and returns a URL that can be given
   * to a {@link java.net.URLClassLoader}.
   */
  @Nullable
  private static URL extractJavaTracingJar() {
    @Nullable final URL resourceURL =
        TranslatingJavacPhaseTracer.class.getResource(JAVAC_TRACING_JAR_RESOURCE_PATH);
    if (resourceURL == null) {
      return null;
    } else if ("file".equals(resourceURL.getProtocol())) {
      // When Buck is running from the repo, the jar is actually already on disk, so no extraction
      // is necessary
      return resourceURL;
    } else {
      // Running from a .pex file, extraction is required
      try (InputStream resourceStream = TranslatingJavacPhaseTracer.class.getResourceAsStream(
          JAVAC_TRACING_JAR_RESOURCE_PATH)) {
        File tempFile = File.createTempFile("javac-tracing", ".jar");
        tempFile.deleteOnExit();
        try (OutputStream tempFileStream = new FileOutputStream(tempFile)) {
          ByteStreams.copy(resourceStream, tempFileStream);
          return tempFile.toURI().toURL();
        }
      } catch (IOException e) {
        LOG.warn(e, "Failed to extract javac tracing jar");
        return null;
      }
    }
  }

  public static void setupTracing(
      BuildTarget invokingTarget,
      ClassLoaderCache classLoaderCache,
      BuckEventBus buckEventBus,
      JavaCompiler.CompilationTask task) {
    if (JAVAC_TRACING_JAR_URL == null) {
      return;
    }

    try {
      // TracingTaskListener is an implementation of com.sun.source.util.TaskListener that traces
      // the TaskEvents to a JavacPhaseTracer. TaskListener is a public API that is packaged in the
      // javac compiler JAR. However, Buck allows Java rules to supply custom compiler JARs. In
      // order to implement TaskListener, then, TracingTaskListener must be loaded in a ClassLoader
      // that has access to the appropriate compiler JAR.
      final ClassLoader compilerClassLoader = task.getClass().getClassLoader();
      final ClassLoader tracingTaskListenerClassLoader =
          classLoaderCache.getClassLoaderForClassPath(
              compilerClassLoader,
              ImmutableList.of(JAVAC_TRACING_JAR_URL));

      final Class<?> tracingTaskListenerClass = Class.forName(
          "com.facebook.buck.java.tracing.TracingTaskListener",
          false,
          tracingTaskListenerClassLoader);
      final Method setupTracingMethod = tracingTaskListenerClass.getMethod(
          "setupTracing",
          JavaCompiler.CompilationTask.class,
          JavacPhaseTracer.class);
      setupTracingMethod.invoke(
          null,
          task,
          new TranslatingJavacPhaseTracer(new JavacPhaseEventLogger(invokingTarget, buckEventBus)));
    } catch (ReflectiveOperationException e) {
      LOG.warn(
          e,
          "Failed loading TracingTaskListener. " +
              "Perhaps using a compiler that doesn't support com.sun.source.util.JavaTask?");
    }
  }

  public TranslatingJavacPhaseTracer(JavacPhaseEventLogger logger) {
    this.logger = logger;
  }

  @Override
  public void beginParse(String filename) {
    logger.beginParse(filename);
  }

  @Override
  public void endParse() {
    logger.endParse();
  }

  @Override
  public void beginEnter() {
    logger.beginEnter();
  }

  @Override
  public void endEnter(List<String> filenames) {
    logger.endEnter(filenames);
  }

  @Override
  public void beginAnnotationProcessingRound() {
    if (isProcessingAnnotations) {
      logger.endAnnotationProcessingRound(false);
    } else {
      logger.beginAnnotationProcessing();
    }

    isProcessingAnnotations = true;
    roundNumber += 1;
    logger.beginAnnotationProcessingRound(roundNumber);
    logger.beginRunAnnotationProcessors();
  }

  @Override
  public void endAnnotationProcessingRound() {
    logger.endRunAnnotationProcessors();
  }

  @Override
  public void beginAnalyze(String filename, String typename) {
    if (isProcessingAnnotations) {
      logger.endAnnotationProcessingRound(true);
      logger.endAnnotationProcessing();
      isProcessingAnnotations = false;
    }

    logger.beginAnalyze(filename, typename);
  }

  @Override
  public void endAnalyze() {
    logger.endAnalyze();
  }

  @Override
  public void beginGenerate(String filename, String typename) {
    logger.beginGenerate(filename, typename);
  }

  @Override
  public void endGenerate() {
    logger.endGenerate();
  }
}
