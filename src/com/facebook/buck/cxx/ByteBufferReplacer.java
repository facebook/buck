/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs an in-place find-and-replace on {@link ByteBuffer} objects, where the replacements are
 * of equal length to what they're replacing.
 */
public class ByteBufferReplacer {

  private final ImmutableMap<byte[], byte[]> replacements;

  public ByteBufferReplacer(ImmutableMap<byte[], byte[]> replacements) {
    for (Map.Entry<byte[], byte[]> entry : replacements.entrySet()) {
      Preconditions.checkArgument(entry.getKey().length == entry.getValue().length);
    }
    this.replacements = replacements;
  }

  private static byte[] getBytes(String str, Charset charset) {
    byte[] bytes = str.getBytes(charset);

    // Strip off the byte-order-markers for UTF-16.
    if (charset == Charsets.UTF_16) {
      bytes = Arrays.copyOfRange(bytes, 2, bytes.length);
    }

    return bytes;
  }

  /**
   * Build a replacer using the given map of paths to replacement paths, using {@code charset}
   * to convert to underlying byte arrays.  If the replacement paths are not long enough, use
   * the given path separator to fill.
   */
  public static ByteBufferReplacer fromPaths(
      ImmutableMap<Path, Path> paths,
      char separator,
      Charset charset) {

    ImmutableMap.Builder<byte[], byte[]> replacements = ImmutableMap.builder();

    for (Map.Entry<Path, Path> entry : paths.entrySet()) {
      String original = entry.getKey().toString();
      String replacement = entry.getValue().toString();

      // If the replacement string is too small, keep adding the path separator until it's long
      // enough.
      while (getBytes(original, charset).length > getBytes(replacement, charset).length) {
        replacement += separator;
      }

      // Depending on what was passed in, or the character encoding, we can end up with a
      // replacement string that is too long, which we can't recover from.
      Preconditions.checkArgument(
          getBytes(original, charset).length == getBytes(replacement, charset).length);

      replacements.put(getBytes(original, charset), getBytes(replacement, charset));
    }

    return new ByteBufferReplacer(replacements.build());
  }

  /**
   * Perform an in-place replacement pass over the given buffer (bounded by
   * {@link java.nio.Buffer#position} and {@link java.nio.Buffer#limit}).
   *
   * @param buffer the buffer on which to perform replacements.
   * @param maxReplacements the maximum number of replacements to perform (-1 means unlimited).
   * @return the number of replacements that happened.
   */
  public int replace(ByteBuffer buffer, int maxReplacements) {
    CharSequence charSequence = new ByteBufferCharSequence(buffer);
    int position = buffer.position();
    int numReplacements = 0;

    for (Map.Entry<byte[], byte[]> entry : replacements.entrySet()) {
      byte[] value = entry.getValue();

      // Since we can't use Pattern on a byte[], we need to convert our search byte array
      // to a string.  To do this we use ISO-8859-1 since it maps 1-to-1 in 0-0xFF range.
      Pattern pattern = Pattern.compile(
          new String(entry.getKey(), Charsets.ISO_8859_1),
          Pattern.LITERAL);
      Matcher matcher = pattern.matcher(charSequence);

      while (matcher.find() && (numReplacements < maxReplacements || maxReplacements == -1)) {
        for (int i = 0; i < value.length; i++) {
          buffer.put(position + matcher.start() + i, value[i]);
        }
        numReplacements += 1;
      }
    }

    return numReplacements;
  }

  public int replace(ByteBuffer buffer) {
    return replace(buffer, -1);
  }

  /**
   * Provides a {@link CharSequence} view of an underlying {@link ByteBuffer} using the ISO-8859-1
   * character encoding.
   */
  private class ByteBufferCharSequence implements CharSequence {

    private final ByteBuffer buffer;

    public ByteBufferCharSequence(ByteBuffer buffer) {
      this.buffer = buffer;
    }

    @Override
    public int length() {
      return buffer.remaining();
    }

    @Override
    public char charAt(int index) {
      // We convert from a byte to a char here just by casting.  This should be fine, since we're
      // performing the search via the ISO-8859-1 charset above.
      return (char) (0xFF & buffer.get(buffer.position() + index));
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      ByteBuffer slice = buffer.duplicate();
      slice.position(buffer.position() + start);
      slice.limit(buffer.position() + end);
      return new ByteBufferCharSequence(slice);
    }

  }

}
