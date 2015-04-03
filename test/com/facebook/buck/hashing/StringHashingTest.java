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

package com.facebook.buck.hashing;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import static org.junit.Assert.assertThat;
import org.junit.Test;

/**
 * Unit tests for {@link StringHashing}.
 */
public class StringHashingTest {
  @Test
  public void emptyStringHasExpectedHash() {
    Hasher hasher = Hashing.sha1().newHasher();
    StringHashing.hashStringAndLength(hasher, "");
    assertThat(
        hasher.hash(),
        equalTo(HashCode.fromString("9069ca78e7450a285173431b3e52c5c25299e473")));
  }

  @Test
  public void separateHashDifferentFromSameStringsConcatenated() {
    Hasher separateHasher = Hashing.sha1().newHasher();
    StringHashing.hashStringAndLength(separateHasher, "foo");
    StringHashing.hashStringAndLength(separateHasher, "bar");
    Hasher concatenatedHasher = Hashing.sha1().newHasher();
    StringHashing.hashStringAndLength(separateHasher, "foobar");
    assertThat(
        separateHasher.hash(),
        not(equalTo(concatenatedHasher.hash())));
  }
}
