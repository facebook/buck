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

package com.facebook.buck.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * A (partial) replacement for {@link java.io.PrintWriter} with methods that throw {@link
 * IOException}. {@link java.io.PrintWriter} explicitly does not throw, and that can catch people
 * off guard.
 *
 * <p>Only the methods that were in use in Buck at the time this class was written have been
 * implemented (including primitive overloads to prevent accidental inefficiencies later). Feel free
 * to add more methods, but please don't go beyond what {@link java.io.PrintWriter} itself has.
 */
public class ThrowingPrintWriter extends OutputStreamWriter {
  public ThrowingPrintWriter(OutputStream out) {
    super(out);
  }

  public ThrowingPrintWriter(OutputStream out, Charset cs) {
    super(out, cs);
  }

  public ThrowingPrintWriter printf(String format, Object... args) throws IOException {
    return format(format, args);
  }

  public ThrowingPrintWriter format(String format, Object... args) throws IOException {
    write(String.format(format, args));

    return this;
  }

  public void println(boolean b) throws IOException {
    println(Boolean.toString(b));
  }

  public void println(char c) throws IOException {
    println(Character.toString(c));
  }

  public void println(int i) throws IOException {
    println(Integer.toString(i));
  }

  public void println(long l) throws IOException {
    println(Long.toString(l));
  }

  public void println(float f) throws IOException {
    println(Float.toString(f));
  }

  public void println(double d) throws IOException {
    println(Double.toString(d));
  }

  public void println(char[] x) throws IOException {
    write(x);
    println();
  }

  public void println(Object o) throws IOException {
    println(String.valueOf(o));
  }

  public void println(String str) throws IOException {
    write(str);
    println();
  }

  public void println() throws IOException {
    write("\n");
  }
}
