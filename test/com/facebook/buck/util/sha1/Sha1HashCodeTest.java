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

package com.facebook.buck.util.sha1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import com.google.common.base.Strings;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.junit.Test;

public class Sha1HashCodeTest {

  @Test
  public void testSha1HashCodeGetHash() {
    Sha1HashCode hash = Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    assertEquals("a002b39af204cdfaa5fdb67816b13867c32ac52c", hash.getHash());
    assertEquals("toString() and getHash() should match.", hash.toString(), hash.getHash());
  }

  @Test(expected = NullPointerException.class)
  public void testSha1HashCodeRejectsNull() {
    Sha1HashCode.of(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testConstructorParamMustBe40Chars() {
    Sha1HashCode.of(Strings.repeat("a", 39));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testConstructorParamMustMatchCharSet() {
    Sha1HashCode.of(Strings.repeat("A", 40));
  }

  @Test
  public void testUpdate() {
    Hasher hasher1 = Hashing.sha1().newHasher();
    Sha1HashCode sha1 = Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    Hasher hasher2 = sha1.update(hasher1);
    assertSame(hasher1, hasher2);

    HashCode expectedHash =
        Hashing.sha1()
            .newHasher()
            .putBytes(
                new byte[] {
                  (byte) 0xa0,
                  (byte) 0x02,
                  (byte) 0xb3,
                  (byte) 0x9a,
                  (byte) 0xf2,
                  (byte) 0x04,
                  (byte) 0xcd,
                  (byte) 0xfa,
                  (byte) 0xa5,
                  (byte) 0xfd,
                  (byte) 0xb6,
                  (byte) 0x78,
                  (byte) 0x16,
                  (byte) 0xb1,
                  (byte) 0x38,
                  (byte) 0x67,
                  (byte) 0xc3,
                  (byte) 0x2a,
                  (byte) 0xc5,
                  (byte) 0x2c,
                })
            .hash();
    HashCode observedHash = hasher1.hash();
    assertEquals(expectedHash, observedHash);
  }

  @Test
  public void testAsHashCode() {
    Sha1HashCode sha1HashCode = Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    HashCode hashCode = sha1HashCode.asHashCode();
    assertEquals(sha1HashCode.toString(), hashCode.toString());
  }

  @Test
  public void testSha1HashCodeSatisfiesEqualsContract() {
    Sha1HashCode hash = Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    assertNotNull(hash);
    assertNotEquals(new Object(), hash);
    assertEquals(hash, Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"));
  }

  @Test
  public void testNotEqualWhenHashesAreNotEqual() {
    Sha1HashCode hash1 = Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    Sha1HashCode hash2 = Sha1HashCode.of("a550e4c6dba0dd24920cb7cbbe7f599b581c69d9");
    assertNotEquals(hash1, hash2);
  }

  @Test
  @SuppressWarnings("PMD.UseAssertEqualsInsteadOfAssertTrue")
  public void testFromTrustedBytesWithValidInput() {
    byte[] bytes =
        new byte[] {
          (byte) 0xa0,
          (byte) 0x02,
          (byte) 0xb3,
          (byte) 0x9a,
          (byte) 0xf2,
          (byte) 0x04,
          (byte) 0xcd,
          (byte) 0xfa,
          (byte) 0xa5,
          (byte) 0xfd,
          (byte) 0xb6,
          (byte) 0x78,
          (byte) 0x16,
          (byte) 0xb1,
          (byte) 0x38,
          (byte) 0x67,
          (byte) 0xc3,
          (byte) 0x2a,
          (byte) 0xc5,
          (byte) 0x2c,
        };
    Sha1HashCode hashCodeFromRawBytes = Sha1HashCode.fromBytes(bytes);
    assertEquals(
        "firstFourBytes should be in reverse order. See Sha1HashCode.BYTE_ORDER_FOR_FIELDS.",
        0x9ab302a0,
        hashCodeFromRawBytes.firstFourBytes);
    assertEquals(
        "nextEightBytes should be in reverse order. See Sha1HashCode.BYTE_ORDER_FOR_FIELDS.",
        0x78b6fda5facd04f2L,
        hashCodeFromRawBytes.nextEightBytes);
    assertEquals(
        "lastEightBytes should be in reverse order. See Sha1HashCode.BYTE_ORDER_FOR_FIELDS.",
        0x2cc52ac36738b116L,
        hashCodeFromRawBytes.lastEightBytes);

    Sha1HashCode hashCodeFromString = Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    assertEquals(hashCodeFromString, hashCodeFromRawBytes);
    assertEquals(0x9ab302a0, hashCodeFromRawBytes.hashCode());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testFromTrustedBytes() {
    byte[] bytes =
        new byte[] {
          (byte) 0xfa, (byte) 0xce, (byte) 0xb0, (byte) 0x0c,
        };
    Sha1HashCode.fromBytes(bytes);
  }
}
