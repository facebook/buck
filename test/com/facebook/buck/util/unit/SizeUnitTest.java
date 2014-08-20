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
package com.facebook.buck.util.unit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SizeUnitTest {

  @Test(expected = NumberFormatException.class)
  public void testInvalidParseBytes() {
    SizeUnit.parseBytes("foobar");
  }

  @Test
  public void testParseBytes() {
    assertEquals(Long.MAX_VALUE, SizeUnit.parseBytes(
        Long.toString(Long.MAX_VALUE) + "00 B"));
    assertEquals(0L, SizeUnit.parseBytes("0GB"));
    assertEquals(123L, SizeUnit.parseBytes("123 B"));
    assertEquals(123L, SizeUnit.parseBytes("123 Bytes"));

    assertEquals(1024L, SizeUnit.parseBytes("1 kb"));
    assertEquals(1536L, SizeUnit.parseBytes("1.5 kb"));
    assertEquals(1024L, SizeUnit.parseBytes("1 kilobytes"));

    assertEquals(10L * 1024L * 1024L, SizeUnit.parseBytes("10 mb"));
    assertEquals(2L * 1024L * 1024L, SizeUnit.parseBytes("2 megabytes"));

    assertEquals(10L * 1024L * 1024L * 1024L, SizeUnit.parseBytes("10 gb"));
    assertEquals(2L * 1024L * 1024L * 1024L, SizeUnit.parseBytes("2 gigabytes"));

    assertEquals(10L * 1024L * 1024L * 1024L * 1024L, SizeUnit.parseBytes("10 tb"));
    assertEquals(42L * 1024L * 1024L * 1024L * 1024L, SizeUnit.parseBytes("42 terabytes"));
  }

  @Test
  public void testBytes()  {
    assertEquals(4L, SizeUnit.BYTES.toBytes(4L));
    assertEquals(0L, SizeUnit.BYTES.toKilobytes(4L));
    assertEquals(2L, SizeUnit.BYTES.toKilobytes(2048L));
    assertEquals(10L, SizeUnit.BYTES.toMegabytes(10L * 1024L * 1024L));
    assertEquals(10L, SizeUnit.BYTES.toGigabytes(10L * 1024L * 1024L * 1024L));
    assertEquals(10L, SizeUnit.BYTES.toTerabytes(10L * 1024L * 1024L * 1024L * 1024L));
  }

  @Test
  public void testKilobytes() {
    assertEquals(2048L, SizeUnit.KILOBYTES.toBytes(2L));
    assertEquals(2048L, SizeUnit.KILOBYTES.toKilobytes(2048L));
    assertEquals(0L, SizeUnit.KILOBYTES.toMegabytes(4L));
    assertEquals(10L, SizeUnit.KILOBYTES.toMegabytes(10L * 1024L));
    assertEquals(10L, SizeUnit.KILOBYTES.toGigabytes(10L * 1024L * 1024L));
    assertEquals(10L, SizeUnit.KILOBYTES.toTerabytes(10L * 1024L * 1024L * 1024L));
  }

  @Test
  public void testMegabytes() {
    assertEquals(1024L * 1024L, SizeUnit.MEGABYTES.toBytes(1L));
    assertEquals(2048L, SizeUnit.MEGABYTES.toKilobytes(2L));
    assertEquals(42L, SizeUnit.MEGABYTES.toMegabytes(42L));
    assertEquals(0L, SizeUnit.MEGABYTES.toGigabytes(4L));
    assertEquals(10L, SizeUnit.MEGABYTES.toGigabytes(10L * 1024L));
    assertEquals(10L, SizeUnit.MEGABYTES.toTerabytes(10L * 1024L * 1024L));
  }

  @Test
  public void testGigabytes() {
    assertEquals(1024L * 1024L * 1024L, SizeUnit.GIGABYTES.toBytes(1L));
    assertEquals(2 * 1024L * 1024L, SizeUnit.GIGABYTES.toKilobytes(2L));
    assertEquals(2048L, SizeUnit.GIGABYTES.toMegabytes(2L));
    assertEquals(42L, SizeUnit.GIGABYTES.toGigabytes(42L));
    assertEquals(0L, SizeUnit.GIGABYTES.toTerabytes(4L));
    assertEquals(10L, SizeUnit.GIGABYTES.toTerabytes(10L * 1024L));
  }

  @Test
  public void testTerabytes() {
    assertEquals(1024L * 1024L * 1024L * 1024L, SizeUnit.TERABYTES.toBytes(1L));
    assertEquals(2 * 1024L * 1024L * 1024L, SizeUnit.TERABYTES.toKilobytes(2L));
    assertEquals(2L * 1024L * 1024L, SizeUnit.TERABYTES.toMegabytes(2L));
    assertEquals(2048L, SizeUnit.TERABYTES.toGigabytes(2L));
    assertEquals(42L, SizeUnit.TERABYTES.toTerabytes(42L));
  }

  @Test
  public void testFormatBytes() {
    assertEquals("0B", SizeUnit.formatBytes(0));
    assertEquals("1B", SizeUnit.formatBytes(1));
    assertEquals("-1B", SizeUnit.formatBytes(-1));
    assertEquals("42B", SizeUnit.formatBytes(42));
    assertEquals("-123B", SizeUnit.formatBytes(-123));
    assertEquals("-1023B", SizeUnit.formatBytes(-1023));
    assertEquals("1023B", SizeUnit.formatBytes(1023));
  }

  @Test
  public void testFormatKilobytes() {
    assertEquals("1KB", SizeUnit.formatBytes(1024));
    assertEquals("-1KB", SizeUnit.formatBytes(-1024));
    assertEquals("-1.1KB", SizeUnit.formatBytes(-1127));
    assertEquals("1.1KB", SizeUnit.formatBytes(1127));
    assertEquals("1.5KB", SizeUnit.formatBytes(1536));
    assertEquals("-1.9KB", SizeUnit.formatBytes(-2047));
    assertEquals("1.9KB", SizeUnit.formatBytes(2047));
    assertEquals("-2KB", SizeUnit.formatBytes(-2048));
    assertEquals("2KB", SizeUnit.formatBytes(2048));
    assertEquals("1023.9KB", SizeUnit.formatBytes(1048575));
    assertEquals("-1023.9KB", SizeUnit.formatBytes(-1048575));
  }

  @Test
  public void testFormatMegabytes() {
    assertEquals("1MB", SizeUnit.formatBytes(1048576));
    assertEquals("-1MB", SizeUnit.formatBytes(-1048576));
    assertEquals("1.5MB", SizeUnit.formatBytes(1572864));
    assertEquals("-1.5MB", SizeUnit.formatBytes(-1572864));
    assertEquals("512MB", SizeUnit.formatBytes(536870912));
    assertEquals("-512MB", SizeUnit.formatBytes(-536870912));
    assertEquals("1023.9MB", SizeUnit.formatBytes(1073741823));
    assertEquals("-1023.9MB", SizeUnit.formatBytes(-1073741823));
  }

  @Test
  public void testFormatGigabytes() {
    assertEquals("1GB", SizeUnit.formatBytes(1073741824));
    assertEquals("-1GB", SizeUnit.formatBytes(-1073741824));
    assertEquals("1.5GB", SizeUnit.formatBytes(1610612736));
    assertEquals("-1.5GB", SizeUnit.formatBytes(-1610612736));
    assertEquals("512GB", SizeUnit.formatBytes(549755813888L));
    assertEquals("-512GB", SizeUnit.formatBytes(-549755813888L));
    assertEquals("1023.9GB", SizeUnit.formatBytes(1099511627775L));
    assertEquals("-1023.9GB", SizeUnit.formatBytes(-1099511627775L));
  }

  @Test
  public void testFormatTerabytes() {
    assertEquals("1TB", SizeUnit.formatBytes(1099511627776L));
    assertEquals("-1TB", SizeUnit.formatBytes(-1099511627776L));
    assertEquals("1.5TB", SizeUnit.formatBytes(1649267441664L));
    assertEquals("-1.5TB", SizeUnit.formatBytes(-1649267441664L));
    assertEquals("512TB", SizeUnit.formatBytes(562949953421312L));
    assertEquals("-512TB", SizeUnit.formatBytes(-562949953421312L));
    assertEquals("1023.9TB", SizeUnit.formatBytes(1125899906842623L));
    assertEquals("-1023.9TB", SizeUnit.formatBytes(-1125899906842623L));

    // We don't yet go up to PB.
    assertEquals("1024TB", SizeUnit.formatBytes(1125899906842624L));
    assertEquals("-1024TB", SizeUnit.formatBytes(-1125899906842624L));
    assertEquals("1048576TB", SizeUnit.formatBytes(1152921504606846976L));
    assertEquals("-1048576TB", SizeUnit.formatBytes(-1152921504606846976L));
  }
}
