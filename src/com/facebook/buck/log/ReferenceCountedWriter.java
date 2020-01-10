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

package com.facebook.buck.log;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ReferenceCountedWriter extends Writer {
  private final AtomicBoolean hasBeenClosed;
  private final AtomicInteger counter;
  private final OutputStreamWriter innerWriter;

  public ReferenceCountedWriter(OutputStreamWriter innerWriter) {
    this(new AtomicInteger(1), innerWriter);
  }

  private ReferenceCountedWriter(AtomicInteger counter, OutputStreamWriter innerWriter) {
    this.hasBeenClosed = new AtomicBoolean(false);
    this.counter = counter;
    this.innerWriter = innerWriter;
  }

  public ReferenceCountedWriter newReference() {
    if (getAndIncrementIfNotZero(counter) == 0) {
      throw new RuntimeException("ReferenceCountedWriter is closed!");
    }
    return new ReferenceCountedWriter(counter, innerWriter);
  }

  @Override
  public void write(char[] cbuf, int off, int len) throws IOException {
    innerWriter.write(cbuf, off, len);
  }

  @Override
  public void flush() throws IOException {
    innerWriter.flush();
  }

  @Override
  public void write(int c) throws IOException {
    innerWriter.write(c);
  }

  @Override
  public void write(char[] cbuf) throws IOException {
    innerWriter.write(cbuf);
  }

  @Override
  public void write(String str) throws IOException {
    innerWriter.write(str);
  }

  @Override
  public void write(String str, int off, int len) throws IOException {
    innerWriter.write(str, off, len);
  }

  @Override
  public Writer append(CharSequence csq) throws IOException {
    return innerWriter.append(csq);
  }

  @Override
  public Writer append(CharSequence csq, int start, int end) throws IOException {
    return innerWriter.append(csq, start, end);
  }

  @Override
  public Writer append(char c) throws IOException {
    return innerWriter.append(c);
  }

  @Override
  public void close() throws IOException {
    // Avoid decrementing more than once from the same ReferenceCounted instance.
    if (hasBeenClosed.getAndSet(true)) {
      return;
    }

    int currentCount = counter.decrementAndGet();
    if (currentCount == 0) {
      innerWriter.close();
    } else {
      // Close implies flush, so if we're not actually closing we should at least flush
      innerWriter.flush();
    }
  }

  private static int getAndIncrementIfNotZero(AtomicInteger counter) {
    for (; ; ) {
      int current = counter.get();
      if (current == 0) {
        return 0;
      }
      int next = current + 1;
      if (counter.compareAndSet(current, next)) {
        return current;
      }
    }
  }
}
