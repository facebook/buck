package com.facebook.buck.android.aapt;


import org.junit.Assert;
import org.junit.Test;

public class RDotTxtEntryTest {
  @Test
  public void testNumValues() {
    RDotTxtEntry rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "");
    Assert.assertEquals(0, rDotTxtEntry.numValues());

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "0x7f010001");
    Assert.assertEquals(0, rDotTxtEntry.numValues());

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "{}");
    Assert.assertEquals(0, rDotTxtEntry.numValues());

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "{  }");
    Assert.assertEquals(0, rDotTxtEntry.numValues());

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "  {  }  ");
    Assert.assertEquals(0, rDotTxtEntry.numValues());

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "{ 0x7f010001 }");
    Assert.assertEquals(1, rDotTxtEntry.numValues());

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "{ 0x7f010001,0x7f010002,0x7f010003 }");
    Assert.assertEquals(3, rDotTxtEntry.numValues());

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "{0x7f010001,0x7f010002,0x7f010003}");
    Assert.assertEquals(3, rDotTxtEntry.numValues());

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.ID, "test_int_array_id", "  {  0x7f010001,0x7f010002,0x7f010003  }  ");
    Assert.assertEquals(3, rDotTxtEntry.numValues());
  }
}
