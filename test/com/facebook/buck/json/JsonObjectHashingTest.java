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

package com.facebook.buck.json;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Collection;
import java.util.Map;

public class JsonObjectHashingTest {
  private Hasher hasher;

  @Before
  public void setupHasher() {
    hasher = Hashing.sha1().newHasher();
  }

  @Test
  public void emptyMapSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.entrySet(), empty());
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("29e24643a6328cb4ea893738b89c63b842ce24e7", hasher.hash().toString());
  }

  @Test
  public void stringSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":\"v\"}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(String.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("d12f81e02f7891e706334281f823c1010c271dd7", hasher.hash().toString());
  }

  @Test
  public void booleanSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":true}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(Boolean.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("ab07e5750e4b34a1efd493ca9965981cec926666", hasher.hash().toString());
  }

  @Test
  public void shortSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":4096}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(Number.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("ed6da6185c0a876fb545b3003952dca8bea2175c", hasher.hash().toString());
  }

  @Test
  public void intSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":64738}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(Number.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("9ce0538522e4b3708d277ea409a7c6b89b36ef81", hasher.hash().toString());
  }

  @Test
  public void longSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":2147483648}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(Number.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("46a04bf8c2272a738bc6f58330b55feb2f6748bb", hasher.hash().toString());
  }

  @Test
  public void floatSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":123.456}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(Number.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("a0593804d2a5f010416eb0e80a30aea639d7b8fc", hasher.hash().toString());
  }

  @Test
  public void doubleSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":1.79769313486231570e+308}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(Number.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("c008259c4ac2f1c1d86d383129d6cb61abfe6580", hasher.hash().toString());
  }

  @Test
  public void nullSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":null}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map, hasKey("k"));
      assertThat(map.get("k"), nullValue());
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("f3d1dd5edf7eacd4a648d5b219bf677f37fc6f48", hasher.hash().toString());
  }

  @Test
  public void arraySha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":[1,2,3]}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(Collection.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("341c75f9118e092512b162fb6dde882f18a86266", hasher.hash().toString());
  }

  @Test
  public void objectSha1Hash() throws IOException {
    try (Reader reader = new StringReader("{\"k\":{\"k2\":\"v2\"}}")) {
      Map<String, Object> map = RawParser.parseFromReader(reader);
      assertThat(map.get("k"), instanceOf(Map.class));
      JsonObjectHashing.hashJsonObject(hasher, map);
    }
    assertEquals("5fe15cee6527c655f4adcbb2eaf819f5f9ad1909", hasher.hash().toString());
  }

  @Test
  public void rawShortSha1Hash() throws IOException {
    // RawParser coerces integers to Long. Let's bypass it to test
    // this code path.
    Short val = 4096;
    JsonObjectHashing.hashJsonObject(hasher, val);
    assertEquals("d12109f0b8cb8b6e48f058f34766226b29d0b2bb", hasher.hash().toString());
  }

  @Test
  public void rawIntSha1Hash() throws IOException {
    Integer val = 64738;
    JsonObjectHashing.hashJsonObject(hasher, val);
    assertEquals("7dbf423da794e905ef3107b7cc46bda8b7977eba", hasher.hash().toString());
  }

  @Test
  public void rawFloatSha1Hash() throws IOException {
    Float val = 123.456f;
    JsonObjectHashing.hashJsonObject(hasher, val);
    assertEquals("fe1a9e33f321868d26f299524c79bf8753ab100b", hasher.hash().toString());
  }

  @Test
  public void mapOrderDoesNotChangeHash() {
    Map<?, ?> map1 = ImmutableMap.of(
        "firstKey", new Integer(24),
        "secondKey", new Float(13.5));
    Map<?, ?> map2 = ImmutableMap.of(
        "secondKey", new Float(13.5),
        "firstKey", new Integer(24));

    Hasher hasher1 = Hashing.sha1().newHasher();
    Hasher hasher2 = Hashing.sha1().newHasher();

    JsonObjectHashing.hashJsonObject(hasher1, map1);
    JsonObjectHashing.hashJsonObject(hasher2, map2);

    assertEquals(hasher1.hash().toString(), hasher2.hash().toString());
  }

}
