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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.common.base.Strings;

import org.junit.Test;

public class Sha1HashCodeTest {

  @Test
  public void testSha1HashCodeGetHash() {
    Sha1HashCode hash = ImmutableSha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    assertEquals("a002b39af204cdfaa5fdb67816b13867c32ac52c", hash.getHash());
    assertEquals("toString() and getHash() should match.", hash.toString(), hash.getHash());
  }

  @Test(expected = NullPointerException.class)
  public void testSha1HashCodeRejectsNull() {
    ImmutableSha1HashCode.of(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testConstructorParamMustBe40Chars() {
    ImmutableSha1HashCode.of(Strings.repeat("a", 39));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testConstructorParamMustMatchCharSet() {
    ImmutableSha1HashCode.of(Strings.repeat("A", 40));
  }

  @Test
  public void testSha1HashCodeSatisfiesEqualsContract() {
    Sha1HashCode hash = ImmutableSha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    assertFalse(hash.equals(null));
    assertFalse(hash.equals(new Object()));
    assertEquals(hash, ImmutableSha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"));
  }

  @Test
  public void testNotEqualWhenHashesAreNotEqual() {
    Sha1HashCode hash1 = ImmutableSha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c");
    Sha1HashCode hash2 = ImmutableSha1HashCode.of("a550e4c6dba0dd24920cb7cbbe7f599b581c69d9");
    assertFalse(hash1.equals(hash2));
  }

}
