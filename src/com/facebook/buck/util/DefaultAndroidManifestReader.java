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

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

public class DefaultAndroidManifestReader implements AndroidManifestReader{

  /**
   * XPath expression to retrieve the names of activities with an intent-filter that gets them
   * to show up in the launcher.
   */
  private static final String XPATH_LAUNCHER_ACTIVITIES =
      "/manifest/application/*[self::activity or self::activity-alias]" +
      "  [intent-filter[action/@android:name='android.intent.action.MAIN' and " +
      "                 category/@android:name='android.intent.category.LAUNCHER']]" +
      "  /@android:name";

  /**
   * XPath expression to get the package.
   * For a manifest as {@code <manifest package="com.facebook.katana" />}, this results in
   * {@code com.facebook.katana}.
   */
  private static final String XPATH_PACKAGE = "/manifest/@package";

  /**
   * XPath expression to get the version code.
   * For a manifest as {@code <manifest android:versionCode="1" />}, this results in
   * {@code 1}.
   */
  private static final String XPATH_VERSION_CODE = "/manifest/@android:versionCode";


  private final XPathExpression packageExpression;
  private final XPathExpression versionCodeExpression;
  private final XPathExpression launchableActivitiesExpression;
  private final Document doc;

  private DefaultAndroidManifestReader(InputSource src) throws IOException {
    try {
      // Parse the XML.
      doc = XmlDomParser.parse(src, true);

      // Compile the XPath expressions.
      XPath xPath = XPathFactory.newInstance().newXPath();
      xPath.setNamespaceContext(androidNamespaceContext);
      launchableActivitiesExpression = xPath.compile(XPATH_LAUNCHER_ACTIVITIES);
      packageExpression = xPath.compile(XPATH_PACKAGE);
      versionCodeExpression = xPath.compile(XPATH_VERSION_CODE);
    } catch (XPathExpressionException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public List<String> getLauncherActivities() {
    try {
      NodeList nodes;
      nodes = (NodeList) launchableActivitiesExpression.evaluate(doc, XPathConstants.NODESET);

      List<String> activities = Lists.newArrayList();
      for (int i = 0; i < nodes.getLength(); i++) {
        activities.add(nodes.item(i).getTextContent());
      }
      return activities;

    } catch (XPathExpressionException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public String getPackage() {
    try {
      return (String) packageExpression.evaluate(doc, XPathConstants.STRING);
    } catch (XPathExpressionException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public String getVersionCode() {
    try {
      return (String) versionCodeExpression.evaluate(doc, XPathConstants.STRING);
    } catch (XPathExpressionException e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * This allows querying the AndroidManifest for e.g. attributes like android:name using XPath
   */
  private static NamespaceContext androidNamespaceContext = new NamespaceContext() {
    @Override
    public Iterator<String> getPrefixes(String namespaceURI) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getPrefix(String namespaceURI) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getNamespaceURI(String prefix) {
      if (prefix.equals("android")) {
        return "http://schemas.android.com/apk/res/android";
      } else {
        throw new IllegalArgumentException();
      }
    }
  };

  /**
   * Parses an XML given via its path and returns an {@link AndroidManifestReader} for it.
   * @param path path to an AndroidManifest.xml file
   * @return an {@code AndroidManifestReader} for {@code path}
   * @throws IOException
   */
  public static AndroidManifestReader forPath(Path path) throws IOException {
    Reader reader = Files.newBufferedReader(path, Charsets.UTF_8);
    return forReader(reader);
  }

  /**
   * Parses an XML given as a string and returns an {@link AndroidManifestReader} for it.
   * @param xmlString a string representation of an XML document
   * @return an {@code AndroidManifestReader} for the XML document
   * @throws IOException
   */
  public static AndroidManifestReader forString(String xmlString) throws IOException {
    return forReader(new StringReader(xmlString));
  }

  private static AndroidManifestReader forReader(Reader reader) throws IOException {
    return new DefaultAndroidManifestReader(new InputSource(reader));
  }

}
