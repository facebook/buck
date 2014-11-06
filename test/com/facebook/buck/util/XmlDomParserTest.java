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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

public class XmlDomParserTest {

  private static class StringReaderForCloseCheck extends StringReader {
    private boolean isClosed = false;

    public StringReaderForCloseCheck(String s) {
      super(s);
    }

    @Override
    public void close() {
      super.close();
      isClosed = true;
    }

    public boolean isClosed() {
      return isClosed;
    }
  }

  /**
   * Checks that when creating an {@link InputSource} from a {@link Reader} and passing that
   * through {@link XmlDomParser#parse(InputSource,boolean)}, it is closed before the method
   * returns.
   * @see <a href="http://fburl.com/8289364">DocumentBuilder.parse(InputStream)</a>
   * @throws IOException
   */
  @Test
  public void testXmlDomParserClosesReader() throws IOException, SAXException {
    StringReaderForCloseCheck reader = new StringReaderForCloseCheck(
        "<?xml version='1.0'?> <a><b><c></c></b></a>");
    assertFalse(reader.isClosed());
    XmlDomParser.parse(new InputSource(reader), false);
    assertTrue(reader.isClosed());
  }
}
