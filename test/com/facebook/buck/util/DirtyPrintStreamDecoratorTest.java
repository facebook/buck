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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import org.junit.Test;

public class DirtyPrintStreamDecoratorTest {

  @Test
  public void testInitialState() {
    PrintStream delegate = new PrintStream(new ByteArrayOutputStream());

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      assertFalse(dirtyPrintStream.isDirty());
      assertEquals(delegate, dirtyPrintStream.getRawStream());
    }
  }

  @Test
  public void testWriteInt() {
    int[] written = new int[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void write(int b) {
            written[0] = b;
          }
        };
    int n = 42;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.write(n);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(n, written[0]);
    }
  }

  @Test
  public void testWriteBytes() throws IOException {
    byte[][] written = new byte[1][];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void write(byte[] b) {
            written[0] = b;
          }
        };
    byte[] bytes = new byte[] {65, 66, 67};

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.write(bytes);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(bytes, written[0]);
    }
  }

  @Test
  public void testWriteBytesAndOffset() {
    byte[][] writtenBytes = new byte[1][];
    int[] writtenOff = new int[1];
    int[] writtenLen = new int[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          public void write(byte[] buf, int off, int len) {
            writtenBytes[0] = buf;
            writtenOff[0] = off;
            writtenLen[0] = len;
          }
        };
    byte[] bytes = new byte[] {65, 66, 67};

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.write(bytes, 0, 3);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(bytes, writtenBytes[0]);
      assertEquals(0, writtenOff[0]);
      assertEquals(3, writtenLen[0]);
    }
  }

  @Test
  public void testPrintBoolean() {
    boolean[] written = new boolean[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(boolean b) {
            written[0] = b;
          }
        };

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(true);

      assertTrue(dirtyPrintStream.isDirty());
      assertTrue(written[0]);
    }
  }

  @Test
  public void testPrintChar() {
    char[] written = new char[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(char b) {
            written[0] = b;
          }
        };
    char value = 'a';

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintInt() {
    int[] written = new int[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(int b) {
            written[0] = b;
          }
        };
    int value = 42;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintLong() {
    long[] written = new long[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(long b) {
            written[0] = b;
          }
        };
    long value = Long.MAX_VALUE;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintFloat() {
    float[] written = new float[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(float b) {
            written[0] = b;
          }
        };
    float value = 3.14f;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0], 0);
    }
  }

  @Test
  public void testPrintDouble() {
    double[] written = new double[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(double b) {
            written[0] = b;
          }
        };
    double value = Math.PI;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0], 0);
    }
  }

  @Test
  public void testPrintCharArray() {
    char[][] written = new char[1][];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(char[] s) {
            written[0] = s;
          }
        };
    char[] value = new char[] {'h', 'e', 'l', 'l', 'o'};

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintString() {
    String[] written = new String[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(String s) {
            written[0] = s;
          }
        };
    String value = "hello";

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintObject() {
    Object[] written = new Object[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void print(Object obj) {
            written[0] = obj;
          }
        };
    Object value = new Object();

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.print(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintlnWithNoArguments() {
    boolean[] called = new boolean[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println() {
            called[0] = true;
          }
        };

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println();

      assertTrue(dirtyPrintStream.isDirty());
      assertTrue(called[0]);
    }
  }

  @Test
  public void testPrintlnBoolean() {
    boolean[] written = new boolean[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(boolean b) {
            written[0] = b;
          }
        };

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(true);

      assertTrue(dirtyPrintStream.isDirty());
      assertTrue(written[0]);
    }
  }

  @Test
  public void testPrintlnChar() {
    char[] written = new char[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(char c) {
            written[0] = c;
          }
        };
    char value = 'z';

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintlnInt() {
    int[] written = new int[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(int i) {
            written[0] = i;
          }
        };
    int value = 144;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintlnLong() {
    long[] written = new long[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(long l) {
            written[0] = l;
          }
        };
    long value = Long.MIN_VALUE;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintlnFloat() {
    float[] written = new float[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(float f) {
            written[0] = f;
          }
        };
    float value = 2.718f;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0], 0);
    }
  }

  @Test
  public void testPrintlnDouble() {
    double[] written = new double[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(double d) {
            written[0] = d;
          }
        };
    double value = Math.E;

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0], 0);
    }
  }

  @Test
  public void testPrintlnCharArray() {
    char[][] written = new char[1][];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(char[] s) {
            written[0] = s;
          }
        };
    char[] value = new char[] {'a', 'p', 'p', 'l', 'e'};

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintlnString() {
    String[] written = new String[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(String s) {
            written[0] = s;
          }
        };
    String value = "buck";

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintlnObject() {
    Object[] written = new Object[1];
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public void println(Object obj) {
            written[0] = obj;
          }
        };
    Object value = new Object();

    try (DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate)) {
      dirtyPrintStream.println(value);

      assertTrue(dirtyPrintStream.isDirty());
      assertEquals(value, written[0]);
    }
  }

  @Test
  public void testPrintfWithoutLocale() {
    String formatString = "Build target [%s] does not exist.";
    String greeter = "//foo:bar";
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public PrintStream printf(String format, Object... args) {
            assertEquals(format, formatString);
            assertEquals(args[0], greeter);
            return this;
          }
        };

    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.printf(formatString, greeter);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintfWithLocale() {
    Locale locale = Locale.ENGLISH;
    String formatString = "Build target [%s] does not exist.";
    String greeter = "//foo:bar";
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public PrintStream printf(Locale l, String format, Object... args) {
            assertEquals(locale, l);
            assertEquals(format, formatString);
            assertEquals(args[0], greeter);
            return this;
          }
        };

    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.printf(locale, formatString, greeter);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testFormatWithoutLocale() {
    String formatString = "Build target [%s] does not exist.";
    String greeter = "//foo:bar";
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public PrintStream format(String format, Object... args) {
            assertEquals(format, formatString);
            assertEquals(args[0], greeter);
            return this;
          }
        };

    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.format(formatString, greeter);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testFormatWithLocale() {
    Locale locale = Locale.ENGLISH;
    String formatString = "Build target [%s] does not exist.";
    String greeter = "//foo:bar";
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public PrintStream format(Locale l, String format, Object... args) {
            assertEquals(locale, l);
            assertEquals(format, formatString);
            assertEquals(args[0], greeter);
            return this;
          }
        };

    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.format(locale, formatString, greeter);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testAppendCharSequence() {
    CharSequence charSequence = "text";
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public PrintStream append(CharSequence s) {
            assertEquals(s, charSequence);
            return this;
          }
        };

    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.append(charSequence);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testAppendCharSequenceAndOffset() {
    CharSequence charSequence = "text";
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public PrintStream append(CharSequence s, int start, int end) {
            assertEquals(s, charSequence);
            assertEquals(0, start);
            assertEquals(4, end);
            return this;
          }
        };

    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.append(charSequence, 0, 4);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testAppendChar() {
    char value = 'q';
    PrintStream delegate =
        new PrintStream(new ByteArrayOutputStream()) {
          @Override
          public PrintStream append(char c) {
            assertEquals(value, c);
            return this;
          }
        };

    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.append(value);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }
}
