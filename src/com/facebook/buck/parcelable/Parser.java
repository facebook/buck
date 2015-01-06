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

package com.facebook.buck.parcelable;

import com.facebook.buck.util.XmlDomParser;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

public class Parser {

  /** Utility class: do not instantiate. */
  private Parser() {}

  public static ParcelableClass parse(Path xml) throws IOException, SAXException {
    Document doc = XmlDomParser.parse(xml.toFile());

    // packageName, className, creatorClass
    Element classElement = (Element) doc.getElementsByTagName("class").item(0);
    String classNameAttr = getAttribute(classElement, "name");
    Preconditions.checkNotNull(classNameAttr, "name attribute expected");
    int splitIndex = classNameAttr.lastIndexOf('.');
    String packageName = classNameAttr.substring(0, splitIndex);
    String className = classNameAttr.substring(splitIndex + 1);
    String creatorClassName = getAttribute(classElement, "creatorClass");

    // imports
    Element importsElement = (Element) doc.getElementsByTagName("imports").item(0);
    String importsText = importsElement.getTextContent();
    Iterable<String> imports =
        Splitter.on('\n').omitEmptyStrings().trimResults().split(importsText);

    // defaultFieldVisibility
    Element fieldsElement = (Element) doc.getElementsByTagName("fields").item(0);
    String defaultFieldVisibility = getAttribute(fieldsElement, "defaultVisibility");
    if (defaultFieldVisibility == null) {
      defaultFieldVisibility = "private";
    }

    // fields
    ImmutableList.Builder<ParcelableField> fields = ImmutableList.builder();
    NodeList fieldNodes = fieldsElement.getElementsByTagName("field");
    for (int i = 0; i < fieldNodes.getLength(); i++) {
      Element fieldElement = (Element) fieldNodes.item(i);
      String typeAttr = getAttribute(fieldElement, "type");
      Preconditions.checkNotNull(typeAttr, "type attribute expected");
      String nameAttr = getAttribute(fieldElement, "name");
      Preconditions.checkNotNull(nameAttr, "name attribute expected");
      ParcelableField field = new ParcelableField(
          escapeJavaType(typeAttr),
          nameAttr,
          getBooleanAttribute(fieldElement, "mutable"),
          getAttribute(fieldElement, "visibility"),
          getAttribute(fieldElement, "jsonProperty"),
          getAttribute(fieldElement, "defaultValue"));
      fields.add(field);
    }

    return new ParcelableClass(
        packageName,
        imports,
        className,
        creatorClassName == null ? className : creatorClassName,
        defaultFieldVisibility,
        fields.build(),
        getAttribute(classElement, "extends"),
        getAttribute(classElement, "superParams"));
  }

  /**
   * We allow { and } to be used in place of &lt; and &gt; for Java generics in our XML
   * descriptor files.
   */
  private static String escapeJavaType(String type) {
    return type.replace('{', '<').replace('}', '>');
  }

  /**
   * Return null if the attribute is not present rather than the empty string.
   */
  @Nullable
  private static String getAttribute(Element element, String attributeName) {
    if (element.hasAttribute(attributeName)) {
      return element.getAttribute(attributeName);
    } else {
      return null;
    }
  }

  private static boolean getBooleanAttribute(Element element, String attributeName) {
    String value = getAttribute(element, attributeName);
    return value != null ? Boolean.parseBoolean(value) : false;
  }
}
