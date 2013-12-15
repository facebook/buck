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

/**
 * Decorator of PrintStreams that tracks whether or not that stream has been written to.  This is
 * used to wrap stdout and stderr to track if anyone else but the class responsible for formatting
 * output has written to stderr or stdout so we can abort output rendering.
 */
public class DirtyPrintStreamDecorator extends PrintStream {
  private final PrintStream delegate;
  private volatile boolean dirty;

  public DirtyPrintStreamDecorator(PrintStream delegate) {
    super(delegate);
    this.delegate = delegate;
    this.dirty = false;
  }

  public synchronized PrintStream getRawStream() {
    return delegate;
  }

  public synchronized boolean isDirty() {
    return dirty;
  }

  @Override
  public synchronized void write(int b) {
    dirty = true;
    delegate.write(b);
  }

  @Override
  public synchronized void write(byte[] b) throws IOException {
    dirty = true;
    delegate.write(b);
  }

  @Override
  public synchronized void write(byte[] buf, int off, int len) {
    dirty = true;
    delegate.write(buf, off, len);
  }

  @Override
  public synchronized void print(boolean b) {
    dirty = true;
    delegate.print(b);
  }

  @Override
  public synchronized void print(char c) {
    dirty = true;
    delegate.print(c);
  }

  @Override
  public synchronized void print(int i) {
    dirty = true;
    delegate.print(i);
  }

  @Override
  public synchronized void print(long l) {
    dirty = true;
    delegate.print(l);
  }

  @Override
  public synchronized void print(float f) {
    dirty = true;
    delegate.print(f);
  }

  @Override
  public synchronized void print(double d) {
    dirty = true;
    delegate.print(d);
  }

  @Override
  public synchronized void print(char[] s) {
    dirty = true;
    delegate.print(s);
  }

  @Override
  public synchronized void print(String s) {
    dirty = true;
    delegate.print(s);
  }

  @Override
  public synchronized void print(Object obj) {
    dirty = true;
    delegate.print(obj);
  }

  @Override
  public synchronized void println() {
    dirty = true;
    delegate.println();
  }

  @Override
  public synchronized void println(boolean x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized void println(char x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized void println(int x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized void println(long x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized void println(float x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized void println(double x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized void println(char[] x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized void println(String x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized void println(Object x) {
    dirty = true;
    delegate.println(x);
  }

  @Override
  public synchronized PrintStream printf(String format, Object... args) {
    dirty = true;
    delegate.printf(format, args);
    return this;
  }

  @Override
  public synchronized PrintStream printf(Locale l, String format, Object... args) {
    dirty = true;
    delegate.printf(l, format, args);
    return this;
  }

  @Override
  public synchronized PrintStream format(String format, Object... args) {
    dirty = true;
    delegate.format(format, args);
    return this;
  }

  @Override
  public synchronized PrintStream format(Locale l, String format, Object... args) {
    dirty = true;
    delegate.format(l, format, args);
    return this;
  }

  @Override
  public synchronized PrintStream append(CharSequence csq) {
    dirty = true;
    delegate.append(csq);
    return this;
  }

  @Override
  public synchronized PrintStream append(CharSequence csq, int start, int end) {
    dirty = true;
    delegate.append(csq, start, end);
    return this;
  }

  @Override
  public synchronized PrintStream append(char c) {
    dirty = true;
    delegate.append(c);
    return this;
  }
}
