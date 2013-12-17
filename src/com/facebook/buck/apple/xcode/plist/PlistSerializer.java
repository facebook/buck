/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode.plist;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Serializes a plist tree.
 */
public class PlistSerializer implements PlistVisitor<Node> {
  private final Document doc;

  private PlistSerializer(Document doc) {
    this.doc = doc;
  }

  @Override
  public Node visit(PlistArray array) {
    Element element = doc.createElement("array");
    for (PlistValue item : array) {
      element.appendChild(item.acceptVisitor(this));
    }
    return element;
  }

  @Override
  public Node visit(PlistDictionary dictionary) {
    Element elem = doc.createElement("dict");
    for (Map.Entry<String, PlistValue> entry : dictionary) {
      Element key = doc.createElement("key");
      key.appendChild(doc.createTextNode(entry.getKey()));
      elem.appendChild(key);
      elem.appendChild(entry.getValue().acceptVisitor(this));
    }
    return elem;
  }

  @Override
  public Node visit(PlistScalar scalar) {
    Element elem;
    switch (scalar.getType()) {
      case STRING:
        elem = doc.createElement("string");
        elem.appendChild(doc.createTextNode(scalar.getStringValue()));
        return elem;
      case INTEGER:
        elem = doc.createElement("integer");
        elem.appendChild(doc.createTextNode(Integer.toString(scalar.getIntValue())));
        return elem;
      case BOOLEAN:
        return doc.createElement(scalar.getBooleanValue() ? "true" : "false");
      default:
        throw new RuntimeException("Unhandled scalar type: " + scalar.getType());
    }
  }

  public static void serializeToStream(OutputStream stream, PlistValue value) {
    Transformer transformer;

    try {
      transformer = TransformerFactory.newInstance().newTransformer();
    } catch (TransformerConfigurationException e) {
      throw new RuntimeException(e);
    }

    Document document = serializeToXML(value);
    DOMSource source = new DOMSource(document);
    StreamResult result = new StreamResult(stream);

    try {
      transformer.transform(source, result);
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
  }

  public static String serializeToString(PlistValue value) {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    serializeToStream(stream, value);
    return stream.toString();
  }

  public static Document serializeToXML(PlistValue value) {
    DocumentBuilder documentBuilder;
    try {
      documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new RuntimeException(e);
    }
    DOMImplementation domImplementation = documentBuilder.getDOMImplementation();

    DocumentType documentType = domImplementation.createDocumentType(
        "plist",
        "-//Apple//DTD PLIST 1.0//EN",
        "http://www.apple.com/DTDs/PropertyList-1.0.dtd");
    Document doc = domImplementation.createDocument(null, "plist", documentType);
    doc.setXmlVersion("1.0");
    PlistSerializer serializer = new PlistSerializer(doc);
    doc.getDocumentElement().appendChild(value.acceptVisitor(serializer));
    return doc;
  }
}
