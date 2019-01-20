/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger; // NOPMD
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A LogSink that buffers log messages written to a particular logger.
 *
 * <p>Uses JUnit's rule mechanism to take care of installing and removing itself as a Logger
 * handler.
 *
 * <p>Usage:
 *
 * <pre>
 *   @Rule
 *   public final logSink = new TestLogSink(ClassUnderTest.class);
 * </pre>
 */
public final class TestLogSink extends Handler implements TestRule {
  private final List<LogRecord> records = new ArrayList<>();
  private final Class<?> classUnderTest;

  /**
   * Construct a log sink that listens to log message from a particular class's logger.
   *
   * @param classUnderTest Class whose logger should be listened to
   */
  public TestLogSink(Class<?> classUnderTest) {
    this.classUnderTest = classUnderTest;
  }

  /** Retrieve the log records that were published. */
  public List<LogRecord> getRecords() {
    return records;
  }

  /**
   * Construct a hamcrest matcher that matches on the {@code LogRecord}'s message field.
   *
   * @param messageMatcher Matcher for the message.
   */
  public static Matcher<LogRecord> logRecordWithMessage(Matcher<String> messageMatcher) {
    return new FeatureMatcher<LogRecord, String>(
        messageMatcher, "a LogRecord with message", "message") {
      @Override
      protected String featureValueOf(LogRecord logRecord) {
        return logRecord.getMessage();
      }
    };
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        Logger.getLogger(classUnderTest.getName()).addHandler(TestLogSink.this);
        try {
          base.evaluate();
        } finally {
          Logger.getLogger(classUnderTest.getName()).removeHandler(TestLogSink.this);
        }
      }
    };
  }

  @Override
  public void publish(LogRecord record) {
    records.add(record);
  }

  @Override
  public void flush() {}

  @Override
  public void close() throws SecurityException {}
}
