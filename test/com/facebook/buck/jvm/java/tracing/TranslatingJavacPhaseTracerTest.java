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

package com.facebook.buck.jvm.java.tracing;

import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.google.common.collect.ImmutableList;
import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.easymock.MockType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EasyMockRunner.class)
public class TranslatingJavacPhaseTracerTest {

  @Mock(type = MockType.STRICT)
  private JavacPhaseEventLogger mockLogger;

  private TranslatingJavacPhaseTracer tracer;

  @Before
  public void setUp() {
    tracer = new TranslatingJavacPhaseTracer(mockLogger);
  }

  @Test
  public void testNormalCompilation() {
    mockLogger.beginParse("file1");
    mockLogger.endParse();
    mockLogger.beginParse("file2");
    mockLogger.endParse();
    mockLogger.beginEnter();
    mockLogger.endEnter(ImmutableList.of("file1", "file2"));
    mockLogger.beginAnalyze("file1", "type1");
    mockLogger.endAnalyze();
    mockLogger.beginGenerate("file1", "type1");
    mockLogger.endGenerate();
    mockLogger.beginAnalyze("file2", "type2");
    mockLogger.endAnalyze();
    mockLogger.beginGenerate("file2", "type2");
    mockLogger.endGenerate();

    replay(mockLogger);

    tracer.beginParse("file1");
    tracer.endParse();
    tracer.beginParse("file2");
    tracer.endParse();
    tracer.beginEnter();
    tracer.endEnter(ImmutableList.of("file1", "file2"));
    tracer.beginAnalyze("file1", "type1");
    tracer.endAnalyze();
    tracer.beginGenerate("file1", "type1");
    tracer.endGenerate();
    tracer.beginAnalyze("file2", "type2");
    tracer.endAnalyze();
    tracer.beginGenerate("file2", "type2");
    tracer.endGenerate();
    tracer.close();

    verify(mockLogger);
  }

  @Test
  public void testAnnotationProcessingCompilation() {
    mockLogger.beginParse("file1");
    mockLogger.endParse();
    mockLogger.beginParse("file2");
    mockLogger.endParse();
    mockLogger.beginEnter();
    mockLogger.endEnter(ImmutableList.of("file1", "file2"));
    mockLogger.beginAnnotationProcessing();
    mockLogger.beginAnnotationProcessingRound(1);
    mockLogger.beginRunAnnotationProcessors();
    mockLogger.endRunAnnotationProcessors();
    mockLogger.beginParse("generatedFile1");
    mockLogger.endParse();
    mockLogger.beginEnter();
    mockLogger.endEnter(ImmutableList.of("file1", "file2", "generatedFile1"));
    mockLogger.endAnnotationProcessingRound(false);
    mockLogger.beginAnnotationProcessingRound(2);
    mockLogger.beginRunAnnotationProcessors();
    mockLogger.endRunAnnotationProcessors();
    mockLogger.beginEnter();
    mockLogger.endEnter(ImmutableList.of("file1", "file2", "generatedFile1"));
    mockLogger.endAnnotationProcessingRound(true);
    mockLogger.endAnnotationProcessing();
    mockLogger.beginAnalyze("file1", "type1");
    mockLogger.endAnalyze();
    mockLogger.beginGenerate("file1", "type1");
    mockLogger.endGenerate();
    mockLogger.beginAnalyze("file2", "type2");
    mockLogger.endAnalyze();
    mockLogger.beginGenerate("file2", "type2");
    mockLogger.endGenerate();
    mockLogger.beginAnalyze("generatedFile1", "generatedType1");
    mockLogger.endAnalyze();
    mockLogger.beginGenerate("generatedFile1", "generatedType1");
    mockLogger.endGenerate();

    replay(mockLogger);

    tracer.beginParse("file1");
    tracer.endParse();
    tracer.beginParse("file2");
    tracer.endParse();
    tracer.beginEnter();
    tracer.endEnter(ImmutableList.of("file1", "file2"));
    tracer.beginAnnotationProcessingRound();
    tracer.endAnnotationProcessingRound();
    tracer.beginParse("generatedFile1");
    tracer.endParse();
    tracer.beginEnter();
    tracer.endEnter(ImmutableList.of("file1", "file2", "generatedFile1"));
    tracer.beginAnnotationProcessingRound();
    tracer.endAnnotationProcessingRound();
    tracer.beginEnter();
    tracer.endEnter(ImmutableList.of("file1", "file2", "generatedFile1"));
    tracer.beginAnalyze("file1", "type1");
    tracer.endAnalyze();
    tracer.beginGenerate("file1", "type1");
    tracer.endGenerate();
    tracer.beginAnalyze("file2", "type2");
    tracer.endAnalyze();
    tracer.beginGenerate("file2", "type2");
    tracer.endGenerate();
    tracer.beginAnalyze("generatedFile1", "generatedType1");
    tracer.endAnalyze();
    tracer.beginGenerate("generatedFile1", "generatedType1");
    tracer.endGenerate();
    tracer.close();

    verify(mockLogger);
  }

  /** Tests the event stream as it would be for compilation with the -proc:only option. */
  @Test
  public void testProcOnlyCompilation() {
    logDesiredEventStreamForProcOnly(mockLogger);

    replay(mockLogger);

    traceProcOnlyEvents(tracer);

    verify(mockLogger);
  }

  /** Tests the event stream as it would be for compilation with the -proc:only option. */
  @Test
  public void testProcOnlyCompilationDoubleClose() {
    logDesiredEventStreamForProcOnly(mockLogger);

    replay(mockLogger);

    traceProcOnlyEvents(tracer);
    tracer.close();

    verify(mockLogger);
  }

  private void traceProcOnlyEvents(final TranslatingJavacPhaseTracer tracer) {
    tracer.beginParse("file1");
    tracer.endParse();
    tracer.beginParse("file2");
    tracer.endParse();
    tracer.beginEnter();
    tracer.endEnter(ImmutableList.of("file1", "file2"));
    tracer.beginAnnotationProcessingRound();
    tracer.endAnnotationProcessingRound();
    tracer.beginParse("generatedFile1");
    tracer.endParse();
    tracer.beginEnter();
    tracer.endEnter(ImmutableList.of("file1", "file2", "generatedFile1"));
    tracer.beginAnnotationProcessingRound();
    tracer.endAnnotationProcessingRound();
    tracer.close();
  }

  private void logDesiredEventStreamForProcOnly(final JavacPhaseEventLogger logger) {
    logger.beginParse("file1");
    logger.endParse();
    logger.beginParse("file2");
    logger.endParse();
    logger.beginEnter();
    logger.endEnter(ImmutableList.of("file1", "file2"));
    logger.beginAnnotationProcessing();
    logger.beginAnnotationProcessingRound(1);
    logger.beginRunAnnotationProcessors();
    logger.endRunAnnotationProcessors();
    logger.beginParse("generatedFile1");
    logger.endParse();
    logger.beginEnter();
    logger.endEnter(ImmutableList.of("file1", "file2", "generatedFile1"));
    logger.endAnnotationProcessingRound(false);
    logger.beginAnnotationProcessingRound(2);
    logger.beginRunAnnotationProcessors();
    logger.endRunAnnotationProcessors();
    logger.endAnnotationProcessingRound(true);
    logger.endAnnotationProcessing();
  }
}
