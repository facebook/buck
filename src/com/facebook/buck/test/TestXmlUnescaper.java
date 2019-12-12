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

/**
 * An XML escaper that replaces invalid xml characters with valid ones. The behaviour is intended to
 * match that of guava's XmlEscapers
 */
public abstract class TestXmlUnescaper {

  /**
   * @param str the String with xml escaped characters
   * @return the unescaped string
   */
  public final String unescape(String str) {
    if (str == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(str.length());

    for (int i = 0; i < str.length(); ) {
      i += unescapeStr(str, i, sb);
    }
    return sb.toString();
  }

  protected abstract int unescapeStr(String s, int offset, StringBuilder sb);

  public static final TestXmlUnescaper CONTENT_UNESCAPER = new ContentUnescaper();
  public static final TestXmlUnescaper ATTRIBUTE_UNESCAPER = new AttributeUnescaper();

  private static class ContentUnescaper extends TestXmlUnescaper {

    @Override
    protected int unescapeStr(String s, int offset, StringBuilder sb) {
      if (s.startsWith("&amp;", offset)) {
        sb.append("&");
        return "&amp;".length();
      }
      if (s.startsWith("&lt;", offset)) {
        sb.append("<");
        return "&lt;".length();
      }
      if (s.startsWith("&gt;", offset)) {
        sb.append(">");
        return "&gt;".length();
      }
      sb.append(s.charAt(offset));
      return 1;
    }
  }

  private static class AttributeUnescaper extends ContentUnescaper {

    @Override
    protected int unescapeStr(String s, int offset, StringBuilder sb) {
      if (s.startsWith("&apos;", offset)) {
        sb.append("\'");
        return "&apos;".length();
      }
      if (s.startsWith("&quot;", offset)) {
        sb.append("\"");
        return "&quot;".length();
      }
      if (s.startsWith("&#x9;", offset)) {
        sb.append("\t");
        return "&#x9;".length();
      }
      if (s.startsWith("&#xA;", offset)) {
        sb.append("\n");
        return "&#xA;".length();
      }
      if (s.startsWith("&#xD;", offset)) {
        sb.append("\r");
        return "&#xD;".length();
      }

      return super.unescapeStr(s, offset, sb);
    }
  }
}
