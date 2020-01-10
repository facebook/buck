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

package com.facebook.buck.apple;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.RuntimeJsonMappingException;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.Reader;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Utility class to parse the output from {@code idb}. */
public class IdbOutputParsing {

  private static final Logger LOG = Logger.get(IdbOutputParsing.class);

  /** Callbacks invoked with events emitted by {@code idb}. */
  public interface IdbResultCallback {
    void handleTestResult(ImmutableIdbTestResult result);

    void handleEndOfTests();
  }

  /**
   * Decodes a stream of JSON objects as produced by {@code xctool -reporter json-stream} and
   * invokes the callbacks in {@code eventCallback} with each event in the stream.
   */
  public static void streamOutputFromReader(Reader reader, IdbResultCallback resultCallback) {
    try {
      MappingIterator<ImmutableMap<String, Object>> it =
          ObjectMappers.READER
              .forType(new TypeReference<ImmutableMap<String, Object>>() {})
              .readValues(reader);
      while (it.hasNext()) {
        ImmutableMap<String, Object> element;
        try {
          element = it.next();
        } catch (RuntimeJsonMappingException e) {
          LOG.warn(e, "Couldn't parse JSON object from idb");
          continue;
        }
        LOG.debug("Parsing the idb output %s", element);
        resultCallback.handleTestResult(
            ObjectMappers.convertValue(element, ImmutableIdbTestResult.class));
      }
      resultCallback.handleEndOfTests();
    } catch (IOException e) {
      LOG.warn(e, "Couldn't parse idb JSON stream");
    }
  }

  /**
   * Function that will generate a TestResultSummary from the result of a test printed by idb
   * (ImmutableIdbTestResult)
   */
  public static TestResultSummary testResultSummaryForTestResult(ImmutableIdbTestResult result) {
    long timeMillis = (long) (result.getDuration() * TimeUnit.SECONDS.toMillis(1));
    TestResultSummary testResultSummary =
        new TestResultSummary(
            Objects.requireNonNull(result.getClassName()),
            Objects.requireNonNull(result.getMethodName()),
            result.getPassed() ? ResultType.SUCCESS : ResultType.FAILURE,
            timeMillis,
            formatTestMessage(result),
            formatTestStackTrace(result),
            formatTestStdout(result),
            null // stdErr
            );
    LOG.debug("Test result summary: %s", testResultSummary);
    return testResultSummary;
  }

  @Nullable
  private static String formatTestMessage(ImmutableIdbTestResult result) {
    if (!result.getPassed()) {
      StringBuilder exceptionsMessage = new StringBuilder();
      ImmutableIdbFailureInfo failureInfo = result.getFailureInfo();
      exceptionsMessage
          .append(failureInfo.getFile())
          .append(':')
          .append(failureInfo.getLine())
          .append(": ")
          .append(failureInfo.getMessage());
      return exceptionsMessage.toString();
    }
    return null;
  }

  @Nullable
  private static String formatTestStdout(ImmutableIdbTestResult result) {
    if (result.getLogs().length != 0) {
      StringBuilder logs = new StringBuilder();
      for (String log : result.getLogs()) {
        logs.append(log);
      }
      return logs.toString();
    } else {
      return null;
    }
  }

  @Nullable
  private static String formatTestStackTrace(ImmutableIdbTestResult result) {
    if (result.getCrashed()) {
      return formatTestStdout(result);
    } else {
      return null;
    }
  }
}
