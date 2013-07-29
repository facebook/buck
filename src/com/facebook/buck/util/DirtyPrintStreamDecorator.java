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

import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decorator of PrintStreams that tracks whether or not that stream has been written to.  This is
 * used to wrap stdout and stderr to track if anyone else but the class responsible for formatting
 * output has written to stderr or stdout so we can abort output rendering.
 */
public class DirtyPrintStreamDecorator extends PrintStream {
  private final PrintStream delegate;
  private final AtomicBoolean dirty;

  public DirtyPrintStreamDecorator(PrintStream delegate) {
    super(delegate);
    this.delegate = delegate;
    this.dirty = new AtomicBoolean(false);
  }

  public PrintStream getRawStream() {
    return delegate;
  }

  public boolean isDirty() {
    return dirty.get();
  }

  @Override
  public void write(int b) {
    dirty.set(true);
    super.write(b);
  }

  @Override
  public void write(byte[] buf, int off, int len) {
    dirty.set(true);
    super.write(buf, off, len);
  }

  @Override
  public void print(boolean b) {
    dirty.set(true);
    super.print(b);
  }

  @Override
  public void print(char c) {
    dirty.set(true);
    super.print(c);
  }

  @Override
  public void print(int i) {
    dirty.set(true);
    super.print(i);
  }

  @Override
  public void print(long l) {
    dirty.set(true);
    super.print(l);
  }

  @Override
  public void print(float f) {
    dirty.set(true);
    super.print(f);
  }

  @Override
  public void print(double d) {
    dirty.set(true);
    super.print(d);
  }

  @Override
  public void print(char[] s) {
    dirty.set(true);
    super.print(s);
  }

  @Override
  public void print(String s) {
    dirty.set(true);
    super.print(s);
  }

  @Override
  public void print(Object obj) {
    dirty.set(true);
    super.print(obj);
  }

  @Override
  public void println() {
    dirty.set(true);
    super.println();
  }

  @Override
  public void println(boolean x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public void println(char x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public void println(int x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public void println(long x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public void println(float x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public void println(double x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public void println(char[] x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public void println(String x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public void println(Object x) {
    dirty.set(true);
    super.println(x);
  }

  @Override
  public PrintStream printf(String format, Object... args) {
    dirty.set(true);
    return super.printf(format, args);
  }

  @Override
  public PrintStream printf(Locale l, String format, Object... args) {
    dirty.set(true);
    return super.printf(l, format, args);
  }

  @Override
  public PrintStream format(String format, Object... args) {
    dirty.set(true);
    return super.format(format, args);
  }

  @Override
  public PrintStream format(Locale l, String format, Object... args) {
    dirty.set(true);
    return super.format(l, format, args);
  }

  @Override
  public PrintStream append(CharSequence csq) {
    dirty.set(true);
    return super.append(csq);
  }

  @Override
  public PrintStream append(CharSequence csq, int start, int end) {
    dirty.set(true);
    return super.append(csq, start, end);
  }

  @Override
  public PrintStream append(char c) {
    dirty.set(true);
    return super.append(c);
  }

  @Override
  public void write(byte[] b) throws IOException {
    dirty.set(true);
    super.write(b);
  }
}
