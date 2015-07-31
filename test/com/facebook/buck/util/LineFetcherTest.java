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
package com.facebook.buck.util;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LineFetcherTest {

  private static final String TEST_LINE_1 = "Test1";
  private static final String TEST_LINE_2 = "Test2";

  @Test
  public void testUnterminateLineIsReadAsSingleLine() throws Exception {
    String[] expected = { TEST_LINE_1 };
    verifyLineFetcherResponses(TEST_LINE_1, expected);
  }

  @Test
  public void testNewLineLineEnding() throws Exception {
    verifyLineEndingBehaviour("\n");
  }

  @Test
  public void testCarriageReturnLineEnding() throws Exception {
    verifyLineEndingBehaviour("\r");
  }

  @Test
  public void testDOSLineEndingIsTreatedAsOneLineEnding() throws Exception {
    verifyLineEndingBehaviour("\r\n");
  }

  private void verifyLineEndingBehaviour(String lineEnding) throws Exception {
    String[] expected = { TEST_LINE_1, TEST_LINE_2 };
    verifyLineFetcherResponses(TEST_LINE_1 + lineEnding + TEST_LINE_2, expected);
  }

  @Test
  public void testBufferSizedLine() throws Exception {
    verifyLineEndingAtSpecificPlace(LineFetcher.BUFFER_LENGTH, "\r");
  }

  @Test
  public void testLineOverBufferSize() throws Exception {
    verifyLineEndingAtSpecificPlace(LineFetcher.BUFFER_LENGTH + 1, "\r");
  }

  @Test
  public void testDOSLineEndingStartingOnBuffersEdge() throws Exception {
    verifyLineEndingAtSpecificPlace(LineFetcher.BUFFER_LENGTH - 1, "\r\n");
  }

  @Test
  public void testDoubleBufferSizeLineLength() throws Exception {
    verifyLineEndingAtSpecificPlace(LineFetcher.BUFFER_LENGTH * 2, "\r");
  }

  @Test
  public void testTrippleBufferSizeLineLength() throws Exception {
    verifyLineEndingAtSpecificPlace(LineFetcher.BUFFER_LENGTH * 3, "\r");
  }


  private void verifyLineEndingAtSpecificPlace(int lineEndLocation, String lineEnding)
    throws Exception {
    StringBuilder builder = new StringBuilder(
        lineEndLocation +
        lineEnding.length() +
        TEST_LINE_2.length()
    );
    for (int i = 0; i < lineEndLocation; i++) {
      builder.append('X');
    }

    String[] expected = { builder.toString(), TEST_LINE_2 };

    builder.append(lineEnding);
    builder.append(TEST_LINE_2);

    verifyLineFetcherResponses(builder.toString(), expected);
  }

  private void verifyLineFetcherResponses(String sample, String[] expectedResponses)
      throws Exception {
    try (LineFetcher fetcher = new LineFetcher(new StringReader(sample))) {
      for (int i = 0; i < expectedResponses.length; i++) {
        assertEquals(
            "Mismatch in expected and actual response from LineFetcher",
            expectedResponses[i],
            fetcher.readLine()
        );
      }

      assertNull(
          "LineFetcher had unexpected data after it should have finished",
          fetcher.readLine()
      );
    }
  }
}
