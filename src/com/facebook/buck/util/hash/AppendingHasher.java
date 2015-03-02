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

package com.facebook.buck.util.hash;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.hash.Funnel;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;

import java.nio.charset.Charset;
import java.util.LinkedList;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * {@link Hasher} whose {@code put*} calls are forwarded to a sequence of {@link Hasher} objects.
 * <p>
 * When {@link #hash()} is invoked, the {@link #hash()} method of the first {@link Hasher} in the
 * sequence is invoked, and then the {@link Hasher} is removed from the sequence. This makes it
 * possible to invoke additional {@code put*} methods after {@link #hash()} is invoked, which is not
 * generally true of most implementations of {@link Hasher}. Example:
 * <pre>
 * Hasher appendingHasher = new AppendingHasher(Hashing.sha1(), 2);
 * appendingHasher.putInt(42);
 * HashCode hashOf42 = appendingHasher.hash();
 *
 * // This call would fail if appendingHasher were created via Hashing.sha1().newHasher().
 * appendingHasher.putInt(24);
 * HashCode hashOf42And24 = appendingHasher.hash();
 * </pre>
 */
@NotThreadSafe
public class AppendingHasher implements Hasher {

  @SuppressWarnings("PMD.LooseCoupling")
  private final LinkedList<Hasher> hashers;

  /**
   * Creates a new {@link AppendingHasher} backed by a sequence of {@code numHasher}
   * {@link Hasher}s created from the specified {@link HashFunction}.
   */
  public AppendingHasher(HashFunction hashFunction, int numHashers) {
    Preconditions.checkArgument(numHashers > 0);
    LinkedList<Hasher> hashers = Lists.newLinkedList();
    for (int i = 0; i < numHashers; ++i) {
      hashers.add(hashFunction.newHasher());
    }
    this.hashers = hashers;
  }

  @Override
  public Hasher putByte(byte b) {
    for (Hasher hasher : hashers) {
      hasher.putByte(b);
    }
    return this;
  }

  @Override
  public Hasher putBytes(byte[] bytes) {
    for (Hasher hasher : hashers) {
      hasher.putBytes(bytes);
    }
    return this;
  }

  @Override
  public Hasher putBytes(byte[] bytes, int off, int len) {
    for (Hasher hasher : hashers) {
      hasher.putBytes(bytes, off, len);
    }
    return this;
  }

  @Override
  public Hasher putShort(short s) {
    for (Hasher hasher : hashers) {
      hasher.putShort(s);
    }
    return this;
  }

  @Override
  public Hasher putInt(int i) {
    for (Hasher hasher : hashers) {
      hasher.putInt(i);
    }
    return this;
  }

  @Override
  public Hasher putLong(long l) {
    for (Hasher hasher : hashers) {
      hasher.putLong(l);
    }
    return this;
  }

  @Override
  public Hasher putFloat(float f) {
    for (Hasher hasher : hashers) {
      hasher.putFloat(f);
    }
    return this;
  }

  @Override
  public Hasher putDouble(double d) {
    for (Hasher hasher : hashers) {
      hasher.putDouble(d);
    }
    return this;
  }

  @Override
  public Hasher putBoolean(boolean b) {
    for (Hasher hasher : hashers) {
      hasher.putBoolean(b);
    }
    return this;
  }

  @Override
  public Hasher putChar(char c) {
    for (Hasher hasher : hashers) {
      hasher.putChar(c);
    }
    return this;
  }

  @Override
  public Hasher putUnencodedChars(CharSequence charSequence) {
    for (Hasher hasher : hashers) {
      hasher.putUnencodedChars(charSequence);
    }
    return this;
  }

  @Override
  public Hasher putString(CharSequence charSequence, Charset charset) {
    for (Hasher hasher : hashers) {
      hasher.putString(charSequence, charset);
    }
    return this;
  }

  @Override
  public <T> Hasher putObject(T instance, Funnel<? super T> funnel) {
    for (Hasher hasher : hashers) {
      hasher.putObject(instance, funnel);
    }
    return this;
  }

  @Override
  public HashCode hash() {
    return hashers.removeFirst().hash();
  }
}
