/*
 * Copyright 2017 Google Inc.
 * Copyright 2017-present Facebook, Inc.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Stack;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Locator2;
import org.xml.sax.helpers.DefaultHandler;

/** Builds a DOM tree that maintains element line and column numbers in userData */
public class PositionalXmlHandler extends DefaultHandler {
  public static final String LOCATION_USER_DATA_KEY = "lineLocation";

  @Nullable private Document document;

  @Nullable private Locator2 locator;

  private final Stack<Element> elementStack = new Stack<>();
  private StringBuilder textBuffer = new StringBuilder();

  @Override
  public void startDocument() {
    try {
      DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder documentBuilder = builderFactory.newDocumentBuilder();
      document = documentBuilder.newDocument();
    } catch (ParserConfigurationException ex) {
      throw new RuntimeException("Cannot create document", ex);
    }
  }

  @Override
  public void setDocumentLocator(Locator locator) {
    this.locator = (Locator2) locator;
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes) {
    addText();
    Preconditions.checkNotNull(document);
    Element element = document.createElementNS(uri, qName);
    for (int i = 0; i < attributes.getLength(); i++) {
      element.setAttribute(attributes.getQName(i), attributes.getValue(i));
    }
    element.setUserData(LOCATION_USER_DATA_KEY, getDocumentLocation(), null);
    elementStack.push(element);
  }

  @Override
  public void endElement(String uri, String localName, String qName) {
    addText();
    Element closedElement = elementStack.pop();
    if (elementStack.isEmpty()) { // If this is the root element
      closedElement.setUserData("encoding", getLocatorEncoding(), null);
      Preconditions.checkNotNull(document);
      document.appendChild(closedElement);
    } else {
      Element parentElement = elementStack.peek();
      parentElement.appendChild(closedElement);
    }
  }

  @Override
  public void characters(char ch[], int start, int length) {
    textBuffer.append(ch, start, length);
  }

  /** Returns the text inside the current tag */
  @VisibleForTesting
  void addText() {
    if (textBuffer.length() > 0) {
      Preconditions.checkNotNull(document);
      Element element = elementStack.peek();
      Node textNode = document.createTextNode(textBuffer.toString());
      element.appendChild(textNode);
      textBuffer = new StringBuilder();
    }
  }

  @Nullable
  private DocumentLocation getDocumentLocation() {
    if (locator == null) {
      return null;
    }

    return DocumentLocation.of(locator.getLineNumber() - 1, locator.getColumnNumber() - 1);
  }

  @Nullable
  private String getLocatorEncoding() {
    if (locator == null) {
      return null;
    }

    return locator.getEncoding();
  }

  @Nullable
  Document getDocument() {
    return document;
  }

  @Override
  public void error(SAXParseException ex) throws SAXException {
    // nests ex to conserve exception line number
    throw new SAXException(ex.getMessage(), ex);
  }

  @Override
  public void fatalError(SAXParseException ex) throws SAXException {
    throw new SAXException(ex.getMessage(), ex);
  }

  @Override
  public void warning(SAXParseException exception) { // do nothing
  }
}
