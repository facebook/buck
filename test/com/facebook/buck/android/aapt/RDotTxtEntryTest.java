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

package com.facebook.buck.android.aapt;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import org.junit.Test;

public class RDotTxtEntryTest {

  @Test
  public void testRDotTxtEntryCompareTo() {
    ImmutableList<RDotTxtEntry> entries =
        ImmutableList.of(
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "ActionBar", null),
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "ActionBarLayout", "0x7f060008"),
            new RDotTxtEntry(IdType.INT, RType.STYLEABLE, "ActionBar_background", "2", "ActionBar"),
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "ActionBar_contentInsetEnd", "0", "ActionBar"),
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "ActionBarLayout_android", "0", "ActionBarLayout"),
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "ActionBar_backgroundStack", "1", "ActionBar"));

    ImmutableList<RDotTxtEntry> sortedEntries =
        ImmutableList.copyOf(Ordering.natural().sortedCopy(entries));

    assertEquals(
        ImmutableList.of(
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "ActionBar", null),
            new RDotTxtEntry(IdType.INT, RType.STYLEABLE, "ActionBar_background", "2", "ActionBar"),
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "ActionBar_backgroundStack", "1", "ActionBar"),
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "ActionBar_contentInsetEnd", "0", "ActionBar"),
            new RDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "ActionBarLayout", "0x7f060008"),
            new RDotTxtEntry(
                IdType.INT, RType.STYLEABLE, "ActionBarLayout_android", "0", "ActionBarLayout")),
        sortedEntries);
  }
}
