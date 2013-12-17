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

import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.junit.Test;
import org.w3c.dom.Document;

public class PlistSerializerTest {
  @Test
  public void testSerializeScalar() {
    Document document;

    document = PlistSerializer.serializeToXML(PlistScalar.of(1));
    assertThat(document, hasXPath("/plist/integer[. = \"1\"]"));

    document = PlistSerializer.serializeToXML(PlistScalar.of("foo"));
    assertThat(document, hasXPath("/plist/string[. = \"foo\"]"));

    document = PlistSerializer.serializeToXML(PlistScalar.of(false));
    assertThat(document, hasXPath("/plist/false"));

    document = PlistSerializer.serializeToXML(PlistScalar.of(true));
    assertThat(document, hasXPath("/plist/true"));
  }

  @Test
  public void testSerializeDictionary() {
    PlistDictionary dictionary = new PlistDictionary();
    dictionary.put("key1", PlistScalar.of("foo"));
    dictionary.put("key2", PlistScalar.of("bar"));
    dictionary.put("key3", PlistScalar.of(4));
    Document document = PlistSerializer.serializeToXML(dictionary);

    assertThat(document, hasXPath("/plist/dict[count(*) = 6]"));

    assertThat(document, hasXPath("/plist/dict/key[. = \"key1\"]"));
    assertThat(document, hasXPath(
        "/plist/dict/key[. = \"key1\"]/following-sibling::*[position()=1]/self::string[. = \"foo\"]"));

    assertThat(document, hasXPath("/plist/dict/key[. = \"key2\"]"));
    assertThat(document, hasXPath(
        "/plist/dict/key[. = \"key2\"]/following-sibling::*[position()=1]/self::string[. = \"bar\"]"));

    assertThat(document, hasXPath("/plist/dict/key[. = \"key3\"]"));
    assertThat(document, hasXPath(
        "/plist/dict/key[. = \"key3\"]/following-sibling::*[position()=1]/self::integer[. = \"4\"]"));
  }

  @Test
  public void testSerializeArray() {
    PlistArray array = PlistArray.of(
        PlistScalar.of("foo"),
        PlistScalar.of(4),
        PlistScalar.of(false));
    Document document = PlistSerializer.serializeToXML(array);

    assertThat(document, hasXPath("/plist/array[count(*) = 3]"));

    assertThat(document, hasXPath("/plist/array/*[1]/self::string[. = \"foo\"]"));
    assertThat(document, hasXPath("/plist/array/*[2]/self::integer[. = \"4\"]"));
    assertThat(document, hasXPath("/plist/array/*[3]/self::false"));
  }

  @Test
  public void testSerializeToString() {
    PlistDictionary dictionary = PlistDictionary.of("meaningOfLife", PlistScalar.of(42));
    String pListXml = PlistSerializer.serializeToString(dictionary);
    assertEquals(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
        "<plist>" +
          "<dict>" +
            "<key>meaningOfLife</key><integer>42</integer>" +
          "</dict>" +
        "</plist>",
        pListXml);
  }
}
