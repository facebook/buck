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

package com.facebook.buck.util.xml;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class XmlDomParserWithLineNumbersTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "", tmp);
    workspace.setUp();
  }

  /**
   * Checks that when creating an {@link ByteArrayInputStream} and passing that through {@link
   * XmlDomParserWithLineNumbers#parse}, the resulting document contains correctly set line numbers
   *
   * @see <a href="http://fburl.com/8289364">DocumentBuilder.parse(InputStream)</a>
   * @throws IOException, SAXException
   */
  @Test
  public void testXmlDomParsesLineNumbersCorrectly() throws IOException, SAXException {
    Document dom =
        XmlDomParserWithLineNumbers.parse(
            Files.newInputStream(workspace.resolve(Paths.get("sample.xml"))));

    DocumentLocation documentLocation =
        (DocumentLocation)
            dom.getDocumentElement()
                .getFirstChild()
                .getNextSibling()
                .getUserData(PositionalXmlHandler.LOCATION_USER_DATA_KEY);
    assertEquals(DocumentLocation.of(10, 43), documentLocation);
  }
}
