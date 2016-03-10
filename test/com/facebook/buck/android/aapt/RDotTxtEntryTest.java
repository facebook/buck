/*
 * Copyright 2016-present Facebook, Inc.
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

import org.junit.Assert;
import org.junit.Test;

public class RDotTxtEntryTest {
  @Test
  public void testGetNumArrayValues() {
    RDotTxtEntry rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "");
    Assert.assertEquals(0, rDotTxtEntry.getNumArrayValues());

    rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "0x7f010001");
    Assert.assertEquals(0, rDotTxtEntry.getNumArrayValues());

    rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "{}");
    Assert.assertEquals(0, rDotTxtEntry.getNumArrayValues());

    rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "{  }");
    Assert.assertEquals(0, rDotTxtEntry.getNumArrayValues());

    rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "  {  }  ");
    Assert.assertEquals(0, rDotTxtEntry.getNumArrayValues());

    rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "{ 0x7f010001 }");
    Assert.assertEquals(1, rDotTxtEntry.getNumArrayValues());

    rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "{ 0x7f010001,0x7f010002,0x7f010003 }");
    Assert.assertEquals(3, rDotTxtEntry.getNumArrayValues());

    rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "{0x7f010001,0x7f010002,0x7f010003}");
    Assert.assertEquals(3, rDotTxtEntry.getNumArrayValues());

    rDotTxtEntry = new RDotTxtEntry(
        RDotTxtEntry.IdType.INT_ARRAY,
        RDotTxtEntry.RType.ID,
        "test_int_array_id",
        "  {  0x7f010001,0x7f010002,0x7f010003  }  ");
    Assert.assertEquals(3, rDotTxtEntry.getNumArrayValues());
  }

  @Test(expected = IllegalStateException.class)
  public void testGetNumArrayValuesIfIdTypeNotIntArray() {
    new RDotTxtEntry(
        RDotTxtEntry.IdType.INT,
        RDotTxtEntry.RType.ID,
        "test_int_array_id", "0x7f010001").getNumArrayValues();
  }
}
