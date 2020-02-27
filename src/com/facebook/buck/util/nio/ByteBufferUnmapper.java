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

package com.facebook.buck.util.nio;

import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.util.Scope;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/** Class to unmap ByteBuffer on any Java version. Supports try with resources. */
public class ByteBufferUnmapper implements Scope {
  private static final Unmapper unmapper =
      JavaVersion.getMajorVersion() < 9 ? new UnmapperBeforeJava9() : new UnmapperAfterJava9();

  private final ByteBuffer buffer;

  /**
   * Because "A mapped byte buffer and the file mapping that it represents remain valid until the
   * buffer itself is garbage-collected." we need to clean it manually before the GC is run.
   *
   * @param buffer ByteBuffer to clean.
   */
  private ByteBufferUnmapper(ByteBuffer buffer) {
    this.buffer = buffer;
  }

  public static ByteBufferUnmapper createUnsafe(ByteBuffer buffer) {
    return new ByteBufferUnmapper(buffer);
  }

  @Override
  public void close() {
    if (!buffer.isDirect()) {
      return;
    }
    try {
      unmapper.unmap(buffer);
    } catch (Exception e) {
      throw new Error(e);
    }
  }

  /** Return stored ByteBuffer. */
  public ByteBuffer getByteBuffer() {
    return buffer;
  }

  private interface Unmapper {
    void unmap(ByteBuffer buffer) throws Exception;
  }

  private static class UnmapperAfterJava9 implements Unmapper {
    private static final Object unsafe;
    private static final Method invokeCleaner;

    static {
      try {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        unsafe = unsafeField.get(null);
        invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
      } catch (Exception e) {
        throw new Error(e);
      }
    }

    @Override
    public void unmap(ByteBuffer buffer) throws Exception {
      invokeCleaner.invoke(unsafe, buffer);
    }
  }

  private static class UnmapperBeforeJava9 implements Unmapper {
    private static final Method clean;

    static {
      try {
        clean = Class.forName("sun.misc.Cleaner").getMethod("clean");
        clean.setAccessible(true);
      } catch (Exception e) {
        throw new Error(e);
      }
    }

    @Override
    public void unmap(ByteBuffer buffer) throws Exception {
      Method cleaner = buffer.getClass().getMethod("cleaner");
      cleaner.setAccessible(true);
      clean.invoke(cleaner.invoke(buffer));
    }
  }
}
