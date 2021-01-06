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

    StringBuilder inputBuilder = new StringBuilder();
    inputBuilder.append('\uFFFD'); // out of bounds
    inputBuilder.append('h');
    inputBuilder.append("&amp;");
    inputBuilder.append("&lt;");
    inputBuilder.append('1');
    inputBuilder.append("&gt;");
    String inputStr = inputBuilder.toString();

    StringBuilder outputBuilder = new StringBuilder();
    outputBuilder.append('\uFFFD'); // out of bounds
    outputBuilder.append('h'); // not replaced
    outputBuilder.append('&'); // replaced
    outputBuilder.append('<'); // replaced
    outputBuilder.append('1'); // not replaced
    outputBuilder.append('>'); // replaced
    String outputStr = outputBuilder.toString();

    assertEquals(outputStr, TestXmlUnescaper.CONTENT_UNESCAPER.unescape(inputStr));
  }

  @Test
  public void attributeUnescaperReplacesCharacters() {

    StringBuilder inputBuilder = new StringBuilder();
    inputBuilder.append('\uFFFD'); // out of bounds
    inputBuilder.append('h');
    inputBuilder.append("&amp;");
    inputBuilder.append("&lt;");
    inputBuilder.append('1');
    inputBuilder.append("&gt;");
    inputBuilder.append("&apos;");
    inputBuilder.append("&quot;");
    inputBuilder.append("&#x9;");
    inputBuilder.append('!');
    inputBuilder.append("&#xA;");
    inputBuilder.append("&#xD;");
    String inputStr = inputBuilder.toString();

    StringBuilder outputBuilder = new StringBuilder();
    outputBuilder.append('\uFFFD'); // out of bounds
    outputBuilder.append('h'); // not replaced
    outputBuilder.append('&'); // replaced
    outputBuilder.append('<'); // replaced
    outputBuilder.append('1'); // not replaced
    outputBuilder.append('>'); // replaced
    outputBuilder.append('\''); // replaced
    outputBuilder.append('"'); // replaced
    outputBuilder.append('\t'); // replaced
    outputBuilder.append('!'); // not replaced
    outputBuilder.append('\n'); // replaced
    outputBuilder.append('\r'); // replaced

    String outputStr = outputBuilder.toString();

    assertEquals(outputStr, TestXmlUnescaper.ATTRIBUTE_UNESCAPER.unescape(inputStr));
  }
}
