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

package com.facebook.buck.android.aapt;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class RDotTxtEntryOrderingTest {

  private RDotTxtEntry entry1;
  private RDotTxtEntry entry2;
  private int result;
  private int resultWithValueOrdering;

  @Parameterized.Parameters
  public static Collection<Object[]> testData() {
    return Arrays.asList(
        new Object[][] {
          {
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "ActionBar_contentInsetEnd", "1", "ActionBar"),
            new RDotTxtEntry(
                IdType.INT,
                RType.STYLEABLE,
                "ActionBar_contentInsetEnd__android",
                "0",
                "ActionBar"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "AnchorLayout", "{ 0x7f020001 }"),
            new RDotTxtEntry(
                IdType.INT_ARRAY,
                RType.STYLEABLE,
                "AnchorLayout_Layout",
                "{ 0x7f020002,0x7f020003 }"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "AnchorLayout", "{ 0x7f020001 }"),
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "AnchorLayout_attr1", "0", "AnchorLayout"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "AnchorLayout", "{ 0x7f020001 }"),
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "BlahLayout", "{ 0x7f020002 }"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(
                IdType.INT_ARRAY, RType.STYLEABLE, "AnchorLayout_Layout", "{ 0x7f020001 }"),
            new RDotTxtEntry(
                IdType.INT_ARRAY, RType.STYLEABLE, "BlahLayout_Layout", "{ 0x7f020002 }"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "AnchorLayout_attr1", "0", "AnchorLayout"),
            new RDotTxtEntry(
                IdType.INT_ARRAY, RType.STYLEABLE, "BlahLayout_Layout", "{ 0x7f020002 }"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "AnchorLayout_attr1", "0", "AnchorLayout"),
            new RDotTxtEntry(
                IdType.INT_ARRAY,
                RType.STYLEABLE,
                "AnchorLayout_Layout",
                "{ 0x7f020002,0x7f020003 }"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(IdType.INT, RType.STYLEABLE, "AnchorLayout_z1", "0", "AnchorLayout"),
            new RDotTxtEntry(
                IdType.INT_ARRAY,
                RType.STYLEABLE,
                "AnchorLayout_Layout",
                "{ 0x7f020002,0x7f020003 }"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(IdType.INT, RType.STYLEABLE, "BlahLayout_attr1", "0", "BlahLayout"),
            new RDotTxtEntry(
                IdType.INT_ARRAY, RType.STYLEABLE, "AnchorLayout_Layout", "{ 0x7f020002 }"),
            1,
            1
          },
          {
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "BlahLayout", "{ 0x7f020001 }"),
            new RDotTxtEntry(
                IdType.INT_ARRAY, RType.STYLEABLE, "AnchorLayout_Layout", "{ 0x7f020002 }"),
            1,
            1
          },
          {
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "AlertDialog_android_layout", "0", "AlertDialog"),
            new RDotTxtEntry(
                IdType.INT,
                RType.STYLEABLE,
                "AlertDialog_buttonPanelSideLayout",
                "1",
                "AlertDialog"),
            -1,
            -1
          },
          {
            new RDotTxtEntry(IdType.INT, RType.COLOR, "DuplicateColor", "0x7f020001"),
            new RDotTxtEntry(IdType.INT, RType.COLOR, "DuplicateColor", "0x80020001"),
            0,
            -1
          },
        });
  }

  public RDotTxtEntryOrderingTest(
      RDotTxtEntry entry1, RDotTxtEntry entry2, int result, int resultWithValueOrdering) {
    this.entry1 = entry1;
    this.entry2 = entry2;
    this.result = result;
    this.resultWithValueOrdering = resultWithValueOrdering;
  }

  @Test
  public void testOrdering() {
    assertEquals(result, entry1.compareTo(entry2));
  }

  @Test
  public void testOrderingWithValues() {
    assertEquals(resultWithValueOrdering, entry1.compareWithValue(entry2));
  }
}
