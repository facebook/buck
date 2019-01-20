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
    mockLogger.beginAnalyze();
    mockLogger.endAnalyze(ImmutableList.of("file1"), ImmutableList.of("type1"));
    mockLogger.beginGenerate("file1", "type1");
    mockLogger.endGenerate();
    mockLogger.beginAnalyze();
    mockLogger.endAnalyze(ImmutableList.of("file2"), ImmutableList.of("type2"));
    mockLogger.beginGenerate("file2", "type2");
    mockLogger.endGenerate();

    replay(mockLogger);

    tracer.beginParse("file1");
    tracer.endParse();
    tracer.beginParse("file2");
    tracer.endParse();
    tracer.beginEnter();
    tracer.endEnter(ImmutableList.of("file1", "file2"));
    tracer.beginAnalyze();
    tracer.endAnalyze(ImmutableList.of("file1"), ImmutableList.of("type1"));
    tracer.beginGenerate("file1", "type1");
    tracer.endGenerate();
    tracer.beginAnalyze();
    tracer.endAnalyze(ImmutableList.of("file2"), ImmutableList.of("type2"));
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
    mockLogger.endAnnotationProcessingRound(false);
    mockLogger.beginAnnotationProcessingRound(3);
    mockLogger.beginRunAnnotationProcessors();
    mockLogger.endRunAnnotationProcessors();
    mockLogger.beginParse("lastRoundGenerated");
    mockLogger.endParse();
    mockLogger.beginEnter();
    mockLogger.endEnter(ImmutableList.of("file1", "file2", "generatedFile1", "lastRoundGenerated"));
    mockLogger.endAnnotationProcessingRound(true);
    mockLogger.endAnnotationProcessing();
    mockLogger.beginAnalyze();
    mockLogger.endAnalyze(ImmutableList.of("file1"), ImmutableList.of("type1"));
    mockLogger.beginGenerate("file1", "type1");
    mockLogger.endGenerate();
    mockLogger.beginAnalyze();
    mockLogger.endAnalyze(ImmutableList.of("file2"), ImmutableList.of("type2"));
    mockLogger.beginGenerate("file2", "type2");
    mockLogger.endGenerate();
    mockLogger.beginAnalyze();
    mockLogger.endAnalyze(ImmutableList.of("generatedFile1"), ImmutableList.of("generatedType1"));
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
    tracer.beginAnnotationProcessingRound();
    tracer.endAnnotationProcessingRound();
    tracer.beginParse("lastRoundGenerated");
    tracer.endParse();
    tracer.setIsLastRound(true);
    tracer.beginEnter();
    tracer.endEnter(ImmutableList.of("file1", "file2", "generatedFile1", "lastRoundGenerated"));
    tracer.beginAnalyze();
    tracer.endAnalyze(ImmutableList.of("file1"), ImmutableList.of("type1"));
    tracer.beginGenerate("file1", "type1");
    tracer.endGenerate();
    tracer.beginAnalyze();
    tracer.endAnalyze(ImmutableList.of("file2"), ImmutableList.of("type2"));
    tracer.beginGenerate("file2", "type2");
    tracer.endGenerate();
    tracer.beginAnalyze();
    tracer.endAnalyze(ImmutableList.of("generatedFile1"), ImmutableList.of("generatedType1"));
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

  private void traceProcOnlyEvents(TranslatingJavacPhaseTracer tracer) {
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
    tracer.beginAnnotationProcessingRound();
    tracer.endAnnotationProcessingRound();
    tracer.setIsLastRound(true);
    tracer.close();
  }

  private void logDesiredEventStreamForProcOnly(JavacPhaseEventLogger logger) {
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
    logger.beginEnter();
    logger.endEnter(ImmutableList.of("file1", "file2", "generatedFile1"));
    logger.endAnnotationProcessingRound(false);
    logger.beginAnnotationProcessingRound(3);
    logger.beginRunAnnotationProcessors();
    logger.endRunAnnotationProcessors();
    logger.endAnnotationProcessingRound(true);
    logger.endAnnotationProcessing();
  }
}
