/*
 * Copyright 2015-present Facebook, Inc.
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

import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import java.nio.Buffer;
import java.nio.CharBuffer;

/**
 * Utility class to iterate over the lines present in one or more input
 * strings, buffers, or arrays given a {@link LineHandler} callback.
 * <p>
 * Whenever possible, the input buffer is directly passed back to your
 * {@code LineHandler} callback (with adjusted {@link Buffer#position() position}
 * and {@link Buffer#limit() limit}), to avoid copies and allocations.
 * <p>
 * If a line spans more than one input buffer or string, this class
 * will handle concatenating data until an end-of-line is reached
 * (or the {@code LineHandler} is closed).
 * <p>
 * Supports Unix end-of-line ({@code \n}), Windows end-of-line ({@code \r\n}), and Mac
 * end-of-line ({@code \r}).
 *
 * @see CharLineHandler
 * @see ByteLineHandler
 */
public final class LineIterating {
  private static final int INITIAL_BUFFER_CAPACITY = 256;

  private enum ScanResult {
      NEWLINE,
      CARRIAGE_RETURN,
      OTHER
  }

  private interface BufferOperations<T extends Buffer> {
    T createBuffer(int initialBufferCapacity);
    T createSubBuffer(T buffer, int fromIndex, int toIndex);
    ScanResult scanAt(T buffer, int index);
    void appendBuffers(T buffer, T bufferToAppend);
  }

  private static final BufferOperations<CharBuffer> CHAR_BUFFER_OPERATIONS =
    new BufferOperations<CharBuffer>() {
      @Override
      public CharBuffer createBuffer(int initialBufferCapacity) {
        return CharBuffer.allocate(initialBufferCapacity);
      }

      @Override
      public CharBuffer createSubBuffer(CharBuffer buffer, int fromIndex, int toIndex) {
        CharBuffer subBuffer = buffer.duplicate();
        subBuffer.position(fromIndex).limit(toIndex);
        return subBuffer;
      }

      @Override
      public ScanResult scanAt(CharBuffer buffer, int index) {
        switch (buffer.get(index)) {
          case '\n':
            return ScanResult.NEWLINE;
          case '\r':
            return ScanResult.CARRIAGE_RETURN;
          default:
            return ScanResult.OTHER;
        }
      }

      @Override
      public void appendBuffers(CharBuffer buffer, CharBuffer bufferToAppend) {
        buffer.put(bufferToAppend);
      }
    };

  private static final BufferOperations<ByteBuffer> BYTE_BUFFER_OPERATIONS =
    new BufferOperations<ByteBuffer>() {
      @Override
      public ByteBuffer createBuffer(int initialBufferCapacity) {
        return ByteBuffer.allocate(initialBufferCapacity);
      }

      @Override
      public ByteBuffer createSubBuffer(ByteBuffer buffer, int fromIndex, int toIndex) {
        ByteBuffer subBuffer = buffer.duplicate();
        subBuffer.position(fromIndex).limit(toIndex);
        return subBuffer;
      }

      @Override
      public ScanResult scanAt(ByteBuffer buffer, int index) {
        switch (buffer.get(index)) {
          case 0x0A:
            return ScanResult.NEWLINE;
          case 0x0D:
            return ScanResult.CARRIAGE_RETURN;
          default:
            return ScanResult.OTHER;
        }
      }

      @Override
      public void appendBuffers(ByteBuffer buffer, ByteBuffer bufferToAppend) {
        buffer.put(bufferToAppend);
      }
    };

  // Utility class, do not instantiate.
  private LineIterating() { }

  private abstract static class LineHandler<T extends Buffer> implements AutoCloseable {
    private T buffer;
    private boolean sawCarriageReturn;

    /**
     * Callback handler invoked once per line (not including any
     * end-of-line sequences).
     *
     * @return true to continue iterating over lines, false to stop.
     */
    public abstract boolean handleLine(T line);

    public LineHandler(T buffer) {
      this.buffer = buffer;
      this.sawCarriageReturn = false;
    }

    @Override
    public final void close() {
      if (buffer.position() > 0) {
        if (sawCarriageReturn) {
          buffer.position(buffer.position() - 1);
        }
        buffer.flip();
        handleLine(buffer);
        buffer.clear();
      }
      sawCarriageReturn = false;
    }
  }

  /**
   * Stateful callback handler passed to
   * {@link LineIterating#iterateByLines(CharSequence, CharLineHandler)} and
   * {@link LineIterating#iterateByLines(CharBuffer, CharLineHandler)}.
   * <p>
   * Subclass this and provide a {@link #handleLine(Buffer)} callback
   * to receive each line of input.
   * <p>
   * This class is <i>not</i> thread-safe.
   * <p>
   * The method {@link #handleLine(Buffer)} will be invoked once per line
   * (not including any end-of-line sequences -- note that this means
   * a line can be empty if there's a sequence of EOLs.)
   * <p>
   * You <i>must</i> call {@link #close()} after the last chunk of input has
   * been provided to this object, at which point the last line (if
   * any) will be passed back to {@link #handleLine(Buffer)}.
   */
  public abstract static class CharLineHandler extends LineHandler<CharBuffer> {
    public CharLineHandler() {
      super(CharBuffer.allocate(INITIAL_BUFFER_CAPACITY));
    }

    public CharLineHandler(int initialBufferCapacity) {
      super(CharBuffer.allocate(initialBufferCapacity));
    }
  }

  /**
   * Stateful callback handler passed to
   * {@link LineIterating#iterateByLines(byte[], ByteLineHandler)} and
   * {@link LineIterating#iterateByLines(ByteBuffer, ByteLineHandler)}.
   * <p>
   * Subclass this and provide a {@link #handleLine(Buffer)} callback
   * to receive each line of input.
   * <p>
   * This class is <i>not</i> thread-safe.
   * <p>
   * The method {@link #handleLine(Buffer)} will be invoked once per line
   * (not including any end-of-line sequences -- note that this means
   * a line can be empty if there's a sequence of EOLs.)
   * <p>
   * You <i>must</i> call {@link #close()} after the last chunk of input has
   * been provided to this object, at which point the last line (if
   * any) will be passed back to {@link #handleLine(Buffer)}.
   */
  public abstract static class ByteLineHandler extends LineHandler<ByteBuffer> {
    public ByteLineHandler() {
      super(ByteBuffer.allocate(INITIAL_BUFFER_CAPACITY));
    }

    public ByteLineHandler(int initialBufferCapacity) {
      super(ByteBuffer.allocate(initialBufferCapacity));
    }
  }

  /**
   * Iterates over an input {@link CharSequence string} by lines,
   * invoking your implementation of {@link CharLineHandler#handleLine(Buffer)}
   * once for each line in the input.
   * <p>
   * If your input contains long lines split across multiple strings, you can call this method more
   * than once, passing the same {@link CharLineHandler} to each invocation.
   *
   * @param str Input string containing zero or more lines to be iterated.
   * @param lineHandler Callback to be invoked with each line present in {@code str}.
   */
  public static void iterateByLines(CharSequence str, CharLineHandler lineHandler) {
    iterateBufferByLines(CharBuffer.wrap(str), lineHandler, CHAR_BUFFER_OPERATIONS);
  }

  /**
   * Iterates over an input {@link CharBuffer} by lines, invoking your implementation of
   * {@link CharLineHandler#handleLine(Buffer)} once for each line in
   * the input.
   * <p>
   * If your input contains long lines split across multiple buffers, you can call this method more
   * than once, passing the same {@link CharLineHandler} to each invocation.
   * <p>
   * Consumes the entire {@code buffer}, starting at its current
   * position. After returning, its position is set to its limit.
   *
   * @param buffer Input character buffer containing zero or more lines to be iterated.
   * @param lineHandler Callback to be invoked with each line present in {@code buffer}.
   */
  public static void iterateByLines(CharBuffer buffer, CharLineHandler lineHandler) {
    iterateBufferByLines(buffer, lineHandler, CHAR_BUFFER_OPERATIONS);
  }

  /**
   * Iterates over an input byte array by lines, invoking your implementation of
   * {@link ByteLineHandler#handleLine(Buffer)} once for each line in
   * the input.
   *
   * If your input contains long lines split across multiple byte
   * arrays, you can call this method more than once, passing the same
   * {@link ByteLineHandler} to each invocation.
   *
   * @param bytes Input byte array containing zero or more lines to be iterated.
   * @param lineHandler Callback to be invoked with each line present in {@code bytes}.
   */
  public static void iterateByLines(byte[] bytes, ByteLineHandler lineHandler) {
    iterateBufferByLines(ByteBuffer.wrap(bytes), lineHandler, BYTE_BUFFER_OPERATIONS);
  }

  /**
   * Iterates over an input {@link ByteBuffer} by lines, invoking
   * {@link ByteLineHandler#handleLine(Buffer)} once for each line in
   * the input.
   * <p>
   * If your input contains long lines split across multiple byte
   * arrays, you can call this method more than once, passing the same
   * {@link ByteLineHandler} to each invocation.
   * <p>
   * Consumes the entire {@code buffer}, starting at its current
   * position. After returning, its position is set to its limit.
   *
   * @param buffer Input byte buffer containing zero or more lines to be iterated.
   * @param lineHandler Callback to be invoked with each line present in {@code bytes}.
   */
  public static void iterateByLines(ByteBuffer buffer, ByteLineHandler lineHandler) {
    iterateBufferByLines(buffer, lineHandler, BYTE_BUFFER_OPERATIONS);
  }

  private static <T extends Buffer> void iterateBufferByLines(
      T lineBuffer, LineHandler<T> lineHandler, BufferOperations<T> bufferOperations) {
    int lineStartPos = lineBuffer.position();
    int lineEndPos;
    boolean shouldContinue = true;

    for (lineEndPos = 0; shouldContinue && lineEndPos < lineBuffer.limit(); lineEndPos++) {
      switch (bufferOperations.scanAt(lineBuffer, lineEndPos)) {
        case NEWLINE:
          shouldContinue = dispatchHandler(
              lineBuffer, lineHandler, bufferOperations, lineStartPos, lineEndPos);
          lineStartPos = lineEndPos + 1;
          break;
        case CARRIAGE_RETURN:
          if (lineHandler.sawCarriageReturn) {
            shouldContinue = dispatchHandler(
                lineBuffer, lineHandler, bufferOperations, lineStartPos, lineEndPos);
            // We don't add 1 here because this is the "previous" line's carriage return
            // and the carriage return we're on is the start of the "next" line.
            lineStartPos = lineEndPos;
          }
          lineHandler.sawCarriageReturn = true;
          break;
        case OTHER:
          if (lineHandler.sawCarriageReturn) {
            shouldContinue = dispatchHandler(
                lineBuffer, lineHandler, bufferOperations, lineStartPos, lineEndPos);
            lineStartPos = lineEndPos;
          }
          break;
      }
    }

    appendToLineHandlerBuffer(
        lineHandler,
        bufferOperations,
        bufferOperations.createSubBuffer(lineBuffer, lineStartPos, lineEndPos));

    lineBuffer.position(lineBuffer.limit());
  }

  private static <T extends Buffer> boolean dispatchHandler(
      T buffer,
      LineHandler<T> lineHandler,
      BufferOperations<T> bufferOperations,
      int lineStartPos,
      int lineEndPos) {
    T line;
    boolean shouldContinue;
    if (lineHandler.buffer.position() > 0) {
      // There's left-over data in the line handler buffer from a previous dispatch.
      if (lineHandler.sawCarriageReturn) {
        lineHandler.buffer.position(lineHandler.buffer.position() - 1);
      }
      line = bufferOperations.createSubBuffer(buffer, lineStartPos, lineEndPos);
      appendToLineHandlerBuffer(lineHandler, bufferOperations, line);
      lineHandler.buffer.flip();
      shouldContinue = lineHandler.handleLine(lineHandler.buffer);
      lineHandler.buffer.clear();
    } else {
      // Nothing left over in the line handler buffer. We can directly pass
      // the input to the line handler, avoiding copies or allocations.
      if (lineHandler.sawCarriageReturn) {
        Preconditions.checkState(lineEndPos > 0);
        lineEndPos--;
      }

      int oldPosition = buffer.position();
      int oldLimit = buffer.limit();
      buffer.position(lineStartPos).limit(lineEndPos);
      shouldContinue = lineHandler.handleLine(buffer);
      buffer.position(oldPosition).limit(oldLimit);
    }
    lineHandler.sawCarriageReturn = false;
    return shouldContinue;
  }

  private static <T extends Buffer> void appendToLineHandlerBuffer(
      LineHandler<T> lineHandler,
      BufferOperations<T> bufferOperations,
      T buffer) {
    // We had a partial line left over from the last time we were invoked.
    // Concatenate the two chunks of data and send them together.
    int neededCapacity = lineHandler.buffer.remaining() + buffer.remaining();
    if (lineHandler.buffer.capacity() < neededCapacity) {
      int newCapacity = lineHandler.buffer.capacity();
      while (newCapacity < neededCapacity) {
        newCapacity *= 2;
      }
      T newBuffer = bufferOperations.createBuffer(newCapacity);
      lineHandler.buffer.flip();
      bufferOperations.appendBuffers(newBuffer, lineHandler.buffer);
      lineHandler.buffer = newBuffer;
    }
    bufferOperations.appendBuffers(lineHandler.buffer, buffer);
  }
}
