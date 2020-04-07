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

package com.facebook.buck.test;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TestXmlUnescaperTest {

  @Test
  public void contentUnescaperReplacesCharacters() {

    String inputStr =
        "�" // out of bounds
            + 'h'
            + "&amp;"
            + "&lt;"
            + '1'
            + "&gt;";

    String outputStr =
        "�" // out of bounds
            + 'h' // not replaced
            + '&' // replaced
            + '<' // replaced
            + '1' // not replaced
            + '>' // replaced
        ;

    assertEquals(outputStr, TestXmlUnescaper.CONTENT_UNESCAPER.unescape(inputStr));
  }

  @Test
  public void attributeUnescaperReplacesCharacters() {

    String inputStr =
        "�" // out of bounds
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

    String outputStr =
        "�" // out of bounds
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

    assertEquals(outputStr, TestXmlUnescaper.ATTRIBUTE_UNESCAPER.unescape(inputStr));
  }
}
