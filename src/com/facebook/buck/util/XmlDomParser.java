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

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class XmlDomParser {

  /** Utility class: do not instantiate. */
  private XmlDomParser() {}

  public static Document parse(Path xml) throws IOException, SAXException {
    try (InputStream is = Files.newInputStream(xml)) {
      return parse(is);
    }
  }

  public static Document parse(String xmlContents) throws IOException, SAXException {
    return parse(new ByteArrayInputStream(xmlContents.getBytes()));
  }

  public static Document parse(InputStream stream) throws IOException, SAXException {
    return parse(new InputSource(stream), /* namespaceAware */ false);
  }

  public static Document parse(InputSource xml, boolean namespaceAware)
      throws IOException, SAXException {
    DocumentBuilder docBuilder;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      if (namespaceAware) {
        factory.setNamespaceAware(namespaceAware);
      }
      docBuilder = factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new RuntimeException(e);
    }

    return docBuilder.parse(xml);
  }
}
