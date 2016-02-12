package com.facebook.buck.android.aapt;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Set;

public class AaptResourceCollectorTest {

  private AaptResourceCollector aaptResourceCollector;

  @Before
  public void setUp() throws IOException {
    aaptResourceCollector = new AaptResourceCollector();
  }

  @Test
  public void testAddResourceIfNotPresent() {
    Assert.assertEquals(0, aaptResourceCollector.getResources().size());

    RDotTxtEntry rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT, RDotTxtEntry.RType.ID, "test_int_id_1", "0x7ffffffe");
    aaptResourceCollector.addResourceIfNotPresent(rDotTxtEntry);

    Set<RDotTxtEntry> resources = aaptResourceCollector.getResources();
    Assert.assertEquals(1, resources.size());
    Assert.assertTrue(resources.contains(rDotTxtEntry));
    Assert.assertNotEquals(rDotTxtEntry.idValue, findResource(resources, rDotTxtEntry).idValue);
  }

  @Test
  public void testAddResourceIfNotPresentIfRDotTxtEntryAlreadyPresent() {
    RDotTxtEntry rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT, RDotTxtEntry.RType.ID, "test_int_id_1", "0x7ffffffe");
    aaptResourceCollector.addResourceIfNotPresent(rDotTxtEntry);
    RDotTxtEntry addedRDotTxtEntry = findResource(aaptResourceCollector.getResources(), rDotTxtEntry);
    aaptResourceCollector.addResourceIfNotPresent(rDotTxtEntry);

    Set<RDotTxtEntry> resources = aaptResourceCollector.getResources();
    Assert.assertEquals(1, resources.size());
    Assert.assertTrue(resources.contains(rDotTxtEntry));
    Assert.assertEquals(addedRDotTxtEntry.idValue, findResource(resources, rDotTxtEntry).idValue);
  }

  @Test
  public void testGetNextIdValue() {
    RDotTxtEntry rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT, RDotTxtEntry.RType.ID, "test_int_id", "0");
    Assert.assertEquals("0x7f010001", aaptResourceCollector.getNextIdValue(rDotTxtEntry));
    Assert.assertEquals("0x7f010002", aaptResourceCollector.getNextIdValue(rDotTxtEntry));

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT, RDotTxtEntry.RType.INTEGER, "test_int_integer", "0");
    Assert.assertEquals("0x7f020001", aaptResourceCollector.getNextIdValue(rDotTxtEntry));
    Assert.assertEquals("0x7f020002", aaptResourceCollector.getNextIdValue(rDotTxtEntry));
  }

  @Test
  public void testGetNextIdValueIfIntStyleable() {
    RDotTxtEntry rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT, RDotTxtEntry.RType.STYLEABLE, "test_int_styleable", "1");
    Assert.assertEquals("1", aaptResourceCollector.getNextIdValue(rDotTxtEntry));

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT, RDotTxtEntry.RType.STYLEABLE, "test_int_styleable", "2");
    Assert.assertEquals("2", aaptResourceCollector.getNextIdValue(rDotTxtEntry));
  }

  @Test
  public void testGetNextIdValueIfIntArray() {
    RDotTxtEntry rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.STYLEABLE, "test_int_styleable", "{  }");
    Assert.assertEquals("{  }", aaptResourceCollector.getNextIdValue(rDotTxtEntry));

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.STYLEABLE, "test_int_styleable", "{ 0x7fffffff }");
    Assert.assertEquals("{ 0x7f010001 }", aaptResourceCollector.getNextIdValue(rDotTxtEntry));

    rDotTxtEntry = new RDotTxtEntry(RDotTxtEntry.IdType.INT_ARRAY, RDotTxtEntry.RType.STYLEABLE, "test_int_styleable", "{ 0x7ffffffe,0x7fffffff }");
    Assert.assertEquals("{ 0x7f010002,0x7f010003 }", aaptResourceCollector.getNextIdValue(rDotTxtEntry));
  }

  @Test
  public void testGetNextIdValueIfNonCustomType() {
    Assert.assertEquals("0x7f010001", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, false));
    Assert.assertEquals("0x7f010002", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, false));
    Assert.assertEquals("0x7f020001", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.INTEGER, false));
    Assert.assertEquals("0x7f020002", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.INTEGER, false));
    Assert.assertEquals("0x7f010003", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, false));
    Assert.assertEquals("0x7f020003", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.INTEGER, false));
  }

  @Test
  public void testGetNextIdValueIfCustomType() {
    Assert.assertEquals("0x7f010001 #", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, true));
    Assert.assertEquals("0x7f010002", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, false));
    Assert.assertEquals("0x7f010003 #", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, true));
  }

  @Test
  public void testGetNextIdValueIfArrayType() {
    Assert.assertEquals("{  }", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, 0));
    Assert.assertEquals("{ 0x7f010001 }", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, 1));
    Assert.assertEquals("{ 0x7f010002,0x7f010003,0x7f010004 }", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.ID, 3));

    Assert.assertEquals("{  }", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.INTEGER, 0));
    Assert.assertEquals("{ 0x7f020001,0x7f020002,0x7f020003 }", aaptResourceCollector.getNextIdValue(RDotTxtEntry.RType.INTEGER, 3));
  }

  protected RDotTxtEntry findResource(Set<RDotTxtEntry> resources, RDotTxtEntry rDotTxtEntry) {
    for(RDotTxtEntry resource : resources) {
      if (rDotTxtEntry.equals(resource)) {
        return resource;
      }
    }

    return null;
  }
}
