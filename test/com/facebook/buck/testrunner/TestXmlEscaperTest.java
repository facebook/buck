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

package com.facebook.buck.testrunner;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TestXmlEscaperTest {

  @Test
  public void contentEscaperReplacesCharacters() {

    String inputStr =
        "\uFFFE" // out of bounds
            + (char) 0x00 // ascii control character to be replaced
            + 'h' // not replaced
            + '&' // replaced
            + '<' // replaced
            + '1' // not replaced
            + '>' // replaced
        ;

    String outputStr =
        "�" // out of bounds
            + '\uFFFD' // ascii control character to be replaced
            + 'h'
            + "&amp;"
            + "&lt;"
            + '1'
            + "&gt;";

    assertEquals(outputStr, TestXmlEscaper.CONTENT_ESCAPER.escape(inputStr));
  }

  @Test
  public void attributeEscaperReplacesCharacters() {

    String inputStr =
        "\uFFFE" // out of bounds
            + (char) 0x00 // ascii control character to be replaced
            + 'h' // not replaced
            + '&' // replaced
            + '<' // replaced
            + '1' // not replaced
            + '>' // replaced
            + '\'' // replaced
            + '"' // replaced
            + '\t' // replaced
            + '!' // not replaced
            + '\n' // replaced
            + '\r' // replaced
        ;

    String outputStr =
        "�" // out of bounds
            + '\uFFFD' // ascii control character to be replaced
            + 'h'
            + "&amp;"
            + "&lt;"
            + '1'
            + "&gt;"
            + "&apos;"
            + "&quot;"
            + "&#x9;"
            + '!'
            + "&#xA;"
            + "&#xD;";

    assertEquals(outputStr, TestXmlEscaper.ATTRIBUTE_ESCAPER.escape(inputStr));
  }
}
