/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Lists;

import org.junit.Test;

import java.util.ArrayList;

public class HiveRowFormatterTest  {

  @Test
  public void creatingRowWithoutColumns() {
    assertEquals("", HiveRowFormatter.newFormatter().build());
    assertEquals("", HiveRowFormatter.newFormatter().build());
  }

  @Test
  public void creatingWithSingleColumn() {
    String text = "My Column";
    assertEquals(
        text,
        HiveRowFormatter.newFormatter().appendString(text).build());
  }

  @Test
  public void creatingWithTwoColumns() {
    String col1 = "My Column";
    String col2 = "My Second Column";
    String hiveRow = HiveRowFormatter.newFormatter()
        .appendString(col1)
        .appendString(col2)
        .build();
    assertEquals("My Column\001My Second Column", hiveRow);
  }

  @Test
  public void creatingForbiddenCharacters() {
    String[] forbidden = new String[] { "\n", "\r", "\001", "\002" };
    String[] escaped = new String[] { "\\n", "\\r", "\\001", "\\002" };
    for (int i = 0; i < forbidden.length; ++i) {
      String col = "My Column " + forbidden[i];
      String hiveRow = HiveRowFormatter.newFormatter()
          .appendString(col)
          .build();
      assertEquals("My Column " + escaped[i], hiveRow);
    }
  }

  @Test
  public void creatingWithIterable() {
    ArrayList<String> elements = Lists.newArrayList(
        "My Element",
        "My Second Element",
        "My last element");
    String hiveRow = HiveRowFormatter.newFormatter()
        .appendStringIterable(elements)
        .build();
    for (String e : elements) {
      assertTrue(hiveRow.contains(e));
    }
    assertEquals("My Element\002My Second Element\002My last element", hiveRow);
  }
}
