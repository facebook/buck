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

    StringBuilder inputBuilder = new StringBuilder();
    inputBuilder.append('\uFFFE'); // out of bounds
    inputBuilder.append((char) 0x00); // ascii control character to be replaced
    inputBuilder.append('h'); // not replaced
    inputBuilder.append('&'); // replaced
    inputBuilder.append('<'); // replaced
    inputBuilder.append('1'); // not replaced
    inputBuilder.append('>'); // replaced
    String inputStr = inputBuilder.toString();

    StringBuilder outputBuilder = new StringBuilder();
    outputBuilder.append('\uFFFD'); // out of bounds
    outputBuilder.append('\uFFFD'); // ascii control character to be replaced
    outputBuilder.append('h');
    outputBuilder.append("&amp;");
    outputBuilder.append("&lt;");
    outputBuilder.append('1');
    outputBuilder.append("&gt;");
    String outputStr = outputBuilder.toString();

    assertEquals(outputStr, TestXmlEscaper.CONTENT_ESCAPER.escape(inputStr));
  }

  @Test
  public void attributeEscaperReplacesCharacters() {

    StringBuilder inputBuilder = new StringBuilder();
    inputBuilder.append('\uFFFE'); // out of bounds
    inputBuilder.append((char) 0x00); // ascii control character to be replaced
    inputBuilder.append('h'); // not replaced
    inputBuilder.append('&'); // replaced
    inputBuilder.append('<'); // replaced
    inputBuilder.append('1'); // not replaced
    inputBuilder.append('>'); // replaced
    inputBuilder.append('\''); // replaced
    inputBuilder.append('"'); // replaced
    inputBuilder.append('\t'); // replaced
    inputBuilder.append('!'); // not replaced
    inputBuilder.append('\n'); // replaced
    inputBuilder.append('\r'); // replaced

    String inputStr = inputBuilder.toString();

    StringBuilder outputBuilder = new StringBuilder();
    outputBuilder.append('\uFFFD'); // out of bounds
    outputBuilder.append('\uFFFD'); // ascii control character to be replaced
    outputBuilder.append('h');
    outputBuilder.append("&amp;");
    outputBuilder.append("&lt;");
    outputBuilder.append('1');
    outputBuilder.append("&gt;");
    outputBuilder.append("&apos;");
    outputBuilder.append("&quot;");
    outputBuilder.append("&#x9;");
    outputBuilder.append('!');
    outputBuilder.append("&#xA;");
    outputBuilder.append("&#xD;");
    String outputStr = outputBuilder.toString();

    assertEquals(outputStr, TestXmlEscaper.ATTRIBUTE_ESCAPER.escape(inputStr));
  }
}
