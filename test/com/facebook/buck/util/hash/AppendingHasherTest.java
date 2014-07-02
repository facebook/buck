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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Funnel;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.hash.PrimitiveSink;

import org.junit.Test;

public class AppendingHasherTest {

  @Test
  public void testAppendingHasher() {
    HashFunction sha1 = Hashing.sha1();
    AppendingHasher appendingHasher = new AppendingHasher(sha1, /* numHashers */ 3);

    Hasher hasher1 = sha1.newHasher();
    Hasher hasher2 = sha1.newHasher();
    Hasher hasher3 = sha1.newHasher();

    // putDouble(Math.E) to all hashers.
    appendingHasher.putDouble(Math.E);
    hasher1.putDouble(Math.E);
    hasher2.putDouble(Math.E);
    hasher3.putDouble(Math.E);

    // putFloat(3.14f) to all hashers.
    appendingHasher.putFloat(3.14f);
    hasher1.putFloat(3.14f);
    hasher2.putFloat(3.14f);
    hasher3.putFloat(3.14f);

    // putBytes(bytes, 2, 7) to all hashers.
    byte[] bytes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    appendingHasher.putBytes(bytes, 2, 7);
    HashCode firstHash = appendingHasher.hash();
    hasher1.putBytes(bytes, 2, 7);
    hasher2.putBytes(bytes, 2, 7);
    hasher3.putBytes(bytes, 2, 7);

    // putLong(8_000_000_000L) to all but the first hasher.
    appendingHasher.putLong(8_000_000_000L);
    HashCode secondHash = appendingHasher.hash();
    hasher2.putLong(8_000_000_000L);
    hasher3.putLong(8_000_000_000L);

    // putUnencodedChars("hello") to all but the first and second hasher.
    appendingHasher.putUnencodedChars("hello");
    HashCode thirdHash = appendingHasher.hash();
    hasher3.putUnencodedChars("hello");

    assertEquals(hasher1.hash(), firstHash);
    assertEquals(hasher2.hash(), secondHash);
    assertEquals(hasher3.hash(), thirdHash);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testAllPutMethods() {
    HashFunction md5 = Hashing.md5();
    Hasher ordinaryHasher = md5.newHasher();
    AppendingHasher appendingHasher = new AppendingHasher(md5, 1 /* numHashers */);

    Iterable<Hasher> hashers = ImmutableList.of(ordinaryHasher, appendingHasher);
    for (Hasher hasher : hashers) {
      assertSame(hasher, hasher.putByte((byte) 42));
      byte[] bytes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
      assertSame(hasher, hasher.putBytes(bytes, 2, 7));
      assertSame(hasher, hasher.putShort((short) 300));
      assertSame(hasher, hasher.putInt(65101));
      assertSame(hasher, hasher.putLong(8_000_000_000L));
      assertSame(hasher, hasher.putFloat(3.14f));
      assertSame(hasher, hasher.putDouble(Math.E));
      assertSame(hasher, hasher.putBoolean(true));
      assertSame(hasher, hasher.putChar('\n'));
      assertSame(hasher, hasher.putUnencodedChars("I like unit tests."));
      assertSame(hasher, hasher.putString("abc", Charsets.US_ASCII));
      assertSame(hasher, hasher.putObject(this.getClass(), TestFunnel.instance));
    }

    assertEquals(ordinaryHasher.hash(), appendingHasher.hash());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNonPositiveNumHashersIsDisallowed() {
    new AppendingHasher(Hashing.adler32(), 0 /* numHashers */);
  }

  @Test(expected = NullPointerException.class)
  public void testNullHashFunctionIsDisallowed() {
    new AppendingHasher(/* hashFunction */ null, 10 /* numHashers */);
  }

  @SuppressWarnings("serial")
  private static class TestFunnel implements Funnel<Class<?>> {

    private static final Funnel<Class<?>> instance = new TestFunnel();

    private TestFunnel() {}

    @Override
    public void funnel(Class<?> from, PrimitiveSink into) {
      into.putUnencodedChars(from.getName());
    }
  }
}
