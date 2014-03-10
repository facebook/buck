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
}
