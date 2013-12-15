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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

public class DirtyPrintStreamDecoratorTest {

  @Test
  public void testInitialState() {
    PrintStream delegate = createMock(PrintStream.class);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    verify(delegate);

    assertFalse(dirtyPrintStream.isDirty());
    assertEquals(delegate, dirtyPrintStream.getRawStream());
  }

  @Test
  public void testWriteInt() {
    PrintStream delegate = createMock(PrintStream.class);
    int n = 42;
    delegate.write(n);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.write(n);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testWriteBytes() throws IOException {
    PrintStream delegate = createMock(PrintStream.class);
    byte[] bytes = new byte[] {65, 66, 67};
    delegate.write(bytes);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.write(bytes);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testWriteBytesAndOffset() {
    PrintStream delegate = createMock(PrintStream.class);
    byte[] bytes = new byte[] {65, 66, 67};
    delegate.write(bytes, 0, 3);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.write(bytes, 0, 3);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintBoolean() {
    PrintStream delegate = createMock(PrintStream.class);
    boolean value = true;
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintChar() {
    PrintStream delegate = createMock(PrintStream.class);
    char value = 'a';
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintInt() {
    PrintStream delegate = createMock(PrintStream.class);
    int value = 42;
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintLong() {
    PrintStream delegate = createMock(PrintStream.class);
    long value = Long.MAX_VALUE;
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintFloat() {
    PrintStream delegate = createMock(PrintStream.class);
    float value = 3.14f;
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintDouble() {
    PrintStream delegate = createMock(PrintStream.class);
    double value = Math.PI;
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintCharArray() {
    PrintStream delegate = createMock(PrintStream.class);
    char[] value = new char[] {'h', 'e', 'l', 'l', 'o'};
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintString() {
    PrintStream delegate = createMock(PrintStream.class);
    String value = "hello";
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintObject() {
    PrintStream delegate = createMock(PrintStream.class);
    Object value = new Object();
    delegate.print(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.print(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnWithNoArguments() {
    PrintStream delegate = createMock(PrintStream.class);
    delegate.println();

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println();
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnBoolean() {
    PrintStream delegate = createMock(PrintStream.class);
    boolean value = false;
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnChar() {
    PrintStream delegate = createMock(PrintStream.class);
    char value = 'z';
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnInt() {
    PrintStream delegate = createMock(PrintStream.class);
    int value = 144;
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnLong() {
    PrintStream delegate = createMock(PrintStream.class);
    long value = Long.MIN_VALUE;
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnFloat() {
    PrintStream delegate = createMock(PrintStream.class);
    float value = 2.718f;
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnDouble() {
    PrintStream delegate = createMock(PrintStream.class);
    double value = Math.E;
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnCharArray() {
    PrintStream delegate = createMock(PrintStream.class);
    char[] value = new char[] {'a', 'p', 'p', 'l', 'e'};
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnString() {
    PrintStream delegate = createMock(PrintStream.class);
    String value = "buck";
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintlnObject() {
    PrintStream delegate = createMock(PrintStream.class);
    Object value = new Object();
    delegate.println(value);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    dirtyPrintStream.println(value);
    verify(delegate);

    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintfWithoutLocale() {
    PrintStream delegate = createMock(PrintStream.class);
    String formatString = "Build target [%s] does not exist.";
    String greeter = "//foo:bar";
    expect(delegate.printf(formatString, greeter)).andReturn(delegate);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.printf(formatString, greeter);
    verify(delegate);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testPrintfWithLocale() {
    PrintStream delegate = createMock(PrintStream.class);
    Locale locale = Locale.ENGLISH;
    String formatString = "Build target [%s] does not exist.";
    String greeter = "//foo:bar";
    expect(delegate.printf(locale, formatString, greeter)).andReturn(delegate);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.printf(locale, formatString, greeter);
    verify(delegate);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testFormatWithoutLocale() {
    PrintStream delegate = createMock(PrintStream.class);
    String formatString = "Build target [%s] does not exist.";
    String greeter = "//foo:bar";
    expect(delegate.format(formatString, greeter)).andReturn(delegate);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.format(formatString, greeter);
    verify(delegate);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testFormatWithLocale() {
    PrintStream delegate = createMock(PrintStream.class);
    Locale locale = Locale.ENGLISH;
    String formatString = "Build target [%s] does not exist.";
    String greeter = "//foo:bar";
    expect(delegate.format(locale, formatString, greeter)).andReturn(delegate);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.format(locale, formatString, greeter);
    verify(delegate);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testAppendCharSequence() {
    PrintStream delegate = createMock(PrintStream.class);
    CharSequence charSequence = "text";
    expect(delegate.append(charSequence)).andReturn(delegate);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.append(charSequence);
    verify(delegate);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testAppendCharSequenceAndOffset() {
    PrintStream delegate = createMock(PrintStream.class);
    CharSequence charSequence = "text";
    expect(delegate.append(charSequence, 0, 4)).andReturn(delegate);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.append(charSequence, 0, 4);
    verify(delegate);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }

  @Test
  public void testAppendChar() {
    PrintStream delegate = createMock(PrintStream.class);
    char value = 'q';
    expect(delegate.append(value)).andReturn(delegate);

    replay(delegate);
    DirtyPrintStreamDecorator dirtyPrintStream = new DirtyPrintStreamDecorator(delegate);
    PrintStream valueToChain = dirtyPrintStream.append(value);
    verify(delegate);

    assertEquals(dirtyPrintStream, valueToChain);
    assertTrue(dirtyPrintStream.isDirty());
  }
}
