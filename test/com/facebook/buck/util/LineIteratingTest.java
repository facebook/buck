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

package com.facebook.buck.util;

import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.is;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

public class LineIteratingTest {
  private TestLineHandler lineHandler;
  private TestByteLineHandler byteLineHandler;

  private static class TestLineHandler extends LineIterating.CharLineHandler {
    public List<String> lines = new ArrayList<>();
    public Predicate<List<String>> shouldContinue = Predicates.alwaysTrue();

    public TestLineHandler() {
      super(/* initialBufferCapacity */ 20);
    }

    @Override
    public boolean handleLine(CharBuffer line) {
      lines.add(line.toString());
      return shouldContinue.apply(lines);
    }
  }

  private static class TestByteLineHandler extends LineIterating.ByteLineHandler {
    public List<String> lines = new ArrayList<>();
    public Predicate<List<String>> shouldContinue = Predicates.alwaysTrue();

    public TestByteLineHandler() {
      super(/* initialBufferCapacity */ 20);
    }

    @Override
    public boolean handleLine(ByteBuffer line) {
      String lineString = StandardCharsets.UTF_8.decode(line).toString();
      lines.add(lineString);
      return shouldContinue.apply(lines);
    }
  }

  @Before
  public void setUp() {
    lineHandler = new TestLineHandler();
    byteLineHandler = new TestByteLineHandler();
  }

  @Test
  public void emptyStringIteratesNoLines() {
    LineIterating.iterateByLines("", lineHandler);
    assertThat(lineHandler.lines, empty());
    lineHandler.close();
    assertThat(lineHandler.lines, empty());
  }

  @Test
  public void stringWithJustUnixEolIteratesEmptyStrings() {
    LineIterating.iterateByLines("\n\n\n", lineHandler);
    assertThat(lineHandler.lines, contains("", "", ""));
    lineHandler.close();
    assertThat(lineHandler.lines, contains("", "", ""));
  }

  @Test
  public void stringWithJustMacEolIteratesEmptyStrings() {
    LineIterating.iterateByLines("\r\r\r", lineHandler);
    // We can't be sure the third newline is not a Windows EOL
    // until closed.
    assertThat(lineHandler.lines, contains("", ""));
    lineHandler.close();
    assertThat(lineHandler.lines, contains("", "", ""));
  }

  @Test
  public void stringWithJustWindowsEolIteratesEmptyStrings() {
    LineIterating.iterateByLines("\r\n\r\n\r\n", lineHandler);
    assertThat(lineHandler.lines, contains("", "", ""));
  }

  @Test
  public void stringWithoutEolIteratesAfterFinish() {
    LineIterating.iterateByLines("foo", lineHandler);
    assertThat(lineHandler.lines, empty());
    lineHandler.close();
    assertThat(lineHandler.lines, contains("foo"));
  }

  @Test
  public void stringWithUnixEolIteratesBeforeFinish() {
    LineIterating.iterateByLines("foo\n", lineHandler);
    assertThat(lineHandler.lines, contains("foo"));
    lineHandler.close();
    assertThat(lineHandler.lines, contains("foo"));
  }

  @Test
  public void stringWithMultipleUnixEolIterateOncePerLineBeforeFinish() {
    LineIterating.iterateByLines("foo\nbar\nbaz\n", lineHandler);
    assertThat(lineHandler.lines, contains("foo", "bar", "baz"));
    lineHandler.close();
    assertThat(lineHandler.lines, contains("foo", "bar", "baz"));
  }

  @Test
  public void stringWithMultipleWindowsCarriageReturnEolIteratesOncePerLine() {
    LineIterating.iterateByLines("foo\r\nbar\r\nbaz\r\n", lineHandler);
    assertThat(lineHandler.lines, contains("foo", "bar", "baz"));
    lineHandler.close();
    assertThat(lineHandler.lines, contains("foo", "bar", "baz"));
  }

  @Test
  public void stringWithMultipleMacCarriageReturnsIteratesOncePerLine() {
    LineIterating.iterateByLines("foo\rbar\rbaz\r", lineHandler);
    // We can't add baz before seeing if this is the last iteration, since there
    // might be a \n on the next iteration.
    assertThat(lineHandler.lines, contains("foo", "bar"));
    lineHandler.close();
    // Now we should get baz.
    assertThat(lineHandler.lines, contains("foo", "bar", "baz"));
  }

  @Test
  public void stringWithMixedLineEndingsIteratesOncePerLine() {
    LineIterating.iterateByLines("foo\nbar\rbaz\r\n", lineHandler);
    assertThat(lineHandler.lines, contains("foo", "bar", "baz"));
    lineHandler.close();
    assertThat(lineHandler.lines, contains("foo", "bar", "baz"));
  }

  @Test
  public void iteratingByLinesWithMultipleInputBuffersGathersInputUntilEolAppears() {
    LineIterating.iterateByLines("foo", lineHandler);
    assertThat(lineHandler.lines, empty());
    LineIterating.iterateByLines("bar", lineHandler);
    assertThat(lineHandler.lines, empty());
    LineIterating.iterateByLines("baz\n", lineHandler);
    assertThat(lineHandler.lines, contains("foobarbaz"));
    lineHandler.close();
    assertThat(lineHandler.lines, contains("foobarbaz"));
  }

  @Test
  public void iteratingByLinesWithMultipleInputBuffersGathersInputUntilCloseCalled() {
    LineIterating.iterateByLines("foo", lineHandler);
    assertThat(lineHandler.lines, empty());
    LineIterating.iterateByLines("bar", lineHandler);
    assertThat(lineHandler.lines, empty());
    LineIterating.iterateByLines("baz", lineHandler);
    assertThat(lineHandler.lines, empty());
    lineHandler.close();
    assertThat(lineHandler.lines, contains("foobarbaz"));
  }

  @Test
  public void goingOverBufferCapacityGrowsBuffer() {
    // The buffer is only 20 bytes long, so write 25 bytes to make it grow.
    LineIterating.iterateByLines("0123456789012345678901234", lineHandler);
    assertThat(lineHandler.lines, empty());
    LineIterating.iterateByLines("\n", lineHandler);
    assertThat(lineHandler.lines, contains("0123456789012345678901234"));
    lineHandler.close();
    assertThat(lineHandler.lines, contains("0123456789012345678901234"));
  }

  @Test
  public void goingOverBufferCapacityGrowsBufferAndReturnsOnClose() {
    // The buffer is only 20 bytes long, so write 25 bytes to make it grow.
    LineIterating.iterateByLines("0123456789012345678901234", lineHandler);
    assertThat(lineHandler.lines, empty());
    lineHandler.close();
    assertThat(lineHandler.lines, contains("0123456789012345678901234"));
  }

  @Test
  public void stopIteratingAfterTwoEol() {
    lineHandler.shouldContinue = new Predicate<List<String>>() {
      @Override
      public boolean apply(List<String> lines) {
        return lines.size() < 2;
      }
    };
    LineIterating.iterateByLines("foo\nbar\nbaz\n", lineHandler);
    assertThat(lineHandler.lines, contains("foo", "bar"));

    // It's kind of an interesting question whether stopping early
    // should buffer the rest of the data and return the lines on
    // close. We'll go with "no".
    lineHandler.close();
    assertThat(lineHandler.lines, contains("foo", "bar"));
  }

  @Test
  public void inputBufferPositionSetToLimitAfterDone() {
    CharBuffer buf = CharBuffer.wrap("foo\nbar\nbaz\n");
    assertThat(buf.position(), equalTo(0));
    assertThat(buf.limit(), equalTo(12));
    LineIterating.iterateByLines(buf, lineHandler);
    assertThat(buf.position(), equalTo(12));
    assertThat(buf.limit(), equalTo(12));
  }

  @Test
  public void inputBufferReusedForCallbacksIfNothingLeft() {
    final CharBuffer buf = CharBuffer.wrap("foo\nbar\nbaz\n");
    LineIterating.CharLineHandler reuseLineHandler = new LineIterating.CharLineHandler(20) {
        @Override
        public boolean handleLine(CharBuffer line) {
          assertThat(line, is(buf));
          return true;
        }
      };

    LineIterating.iterateByLines(buf, reuseLineHandler);
  }

  @Test
  public void inputBufferNotReusedForCallbacksIfDataLeftInLineHandler() {
    final CharBuffer buf = CharBuffer.wrap("bar\n");
    LineIterating.CharLineHandler reuseLineHandler = new LineIterating.CharLineHandler(20) {
        @Override
        public boolean handleLine(CharBuffer line) {
          assertThat(line, not(is(buf)));
          return true;
        }
      };

    LineIterating.iterateByLines("no-newline-yet", reuseLineHandler);
    LineIterating.iterateByLines(buf, reuseLineHandler);
  }

  @Test
  public void emptyByteBufferLineIteration() {
    LineIterating.iterateByLines(StandardCharsets.UTF_8.encode(""), byteLineHandler);
    assertThat(byteLineHandler.lines, empty());
    lineHandler.close();
    assertThat(byteLineHandler.lines, empty());
  }

  @Test
  public void nonEmptyByteBufferLineIteration() {
    LineIterating.iterateByLines(StandardCharsets.UTF_8.encode("foo\nbar\nbaz"), byteLineHandler);
    assertThat(byteLineHandler.lines, contains("foo", "bar"));
    byteLineHandler.close();
    assertThat(byteLineHandler.lines, contains("foo", "bar", "baz"));
  }
}
