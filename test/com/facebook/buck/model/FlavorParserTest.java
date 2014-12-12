/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.model;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;

import static org.junit.Assert.assertThat;

import org.junit.Test;

public class FlavorParserTest {

  @Test
  public void testParseEmpty() {
    FlavorParser flavorParser = new FlavorParser();
    assertThat(
        flavorParser.parseFlavorString(""),
        emptyIterable());
  }

  @Test
  public void testParseSingle() {
    FlavorParser flavorParser = new FlavorParser();
    assertThat(
        flavorParser.parseFlavorString("foo"),
        contains("foo"));
  }

  @Test
  public void testParseMultiple() {
    FlavorParser flavorParser = new FlavorParser();
    assertThat(
        flavorParser.parseFlavorString("foo,bar"),
        contains("foo", "bar"));
  }

  @Test
  public void testParseMultipleWithDeprecated() {
    FlavorParser flavorParser = new FlavorParser();
    assertThat(
        flavorParser.parseFlavorString("foo,dynamic"),
        contains("foo", "shared"));
  }

}
