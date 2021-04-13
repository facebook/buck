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
import java.io.PrintStream;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Decorator of PrintStreams that tracks whether or not that stream has been written to. This is
 * used to wrap stdout and stderr to track if anyone else but the class responsible for formatting
 * output has written to stderr or stdout so we can abort output rendering.
 */
public class DirtyPrintStreamDecorator extends PrintStream {
  private final PrintStream delegate;

  private boolean dirty;

  public DirtyPrintStreamDecorator(PrintStream delegate) {
    super(delegate);
    this.delegate = delegate;
    this.dirty = false;
  }

  /** Raw stream is incompatible with super console. Use carefully. */
  public PrintStream getRawStream() {
    synchronized (ConsoleLock.lock) {
      return delegate;
    }
  }

  public boolean isDirty() {
    synchronized (ConsoleLock.lock) {
      return dirty;
    }
  }

  @Override
  public void flush() {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.flush();
    }
  }

  @Override
  public void close() {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.close();
    }
  }

  @Override
  public void write(int b) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.write(b);
    }
  }

  @Override
  public void write(byte[] b) throws IOException {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.write(b);
    }
  }

  @Override
  public void write(byte[] buf, int off, int len) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.write(buf, off, len);
    }
  }

  @Override
  public void print(boolean b) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(b);
    }
  }

  @Override
  public void print(char c) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(c);
    }
  }

  @Override
  public void print(int i) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(i);
    }
  }

  @Override
  public void print(long l) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(l);
    }
  }

  @Override
  public void print(float f) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(f);
    }
  }

  @Override
  public void print(double d) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(d);
    }
  }

  @Override
  public void print(char[] s) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(s);
    }
  }

  @Override
  public void print(@Nullable String s) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(s);
    }
  }

  @Override
  public void print(Object obj) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.print(obj);
    }
  }

  @Override
  public void println() {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println();
    }
  }

  @Override
  public void println(boolean x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public void println(char x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public void println(int x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public void println(long x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public void println(float x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public void println(double x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public void println(char[] x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public void println(String x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public void println(Object x) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.println(x);
    }
  }

  @Override
  public PrintStream printf(String format, Object... args) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.printf(format, args);
    }
    return this;
  }

  @Override
  public PrintStream printf(Locale l, String format, Object... args) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.printf(l, format, args);
    }
    return this;
  }

  @Override
  public PrintStream format(String format, Object... args) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.format(format, args);
    }
    return this;
  }

  @Override
  public PrintStream format(Locale l, String format, Object... args) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.format(l, format, args);
    }
    return this;
  }

  @Override
  public PrintStream append(CharSequence csq) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.append(csq);
    }
    return this;
  }

  @Override
  public PrintStream append(CharSequence csq, int start, int end) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.append(csq, start, end);
    }
    return this;
  }

  @Override
  public PrintStream append(char c) {
    synchronized (ConsoleLock.lock) {
      dirty = true;
      delegate.append(c);
    }
    return this;
  }
}
