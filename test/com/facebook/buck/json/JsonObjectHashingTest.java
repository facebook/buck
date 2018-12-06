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

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class JsonObjectHashingTest {
  private Hasher hasher;

  @Before
  public void setupHasher() {
    hasher = Hashing.sha1().newHasher();
  }

  @Test
  public void emptyMapSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of());
    assertEquals("29e24643a6328cb4ea893738b89c63b842ce24e7", hasher.hash().toString());
  }

  @Test
  public void stringSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", "v"));
    assertEquals("fe58988370f1bac4597f7cffc04a315d3ea5493d", hasher.hash().toString());
  }

  @Test
  public void booleanSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", true));
    assertEquals("b3913145947a4a567ff95e9a321d4133cfbe602f", hasher.hash().toString());
  }

  @Test
  public void shortSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", 4096L));
    assertEquals("d052359a75b28c9f1097fdbcab6e9f2b52b47193", hasher.hash().toString());
  }

  @Test
  public void intSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", 64738L));
    assertEquals("a41891d41f003499d698a2e7d7aa3f0760a5c6d2", hasher.hash().toString());
  }

  @Test
  public void longSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", 2147483648L));
    assertEquals("5ec3ef74c52a97a02c1367767ae81a47554c36e4", hasher.hash().toString());
  }

  @Test
  public void floatSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", 123.456));
    assertEquals("c1c8a5cd9eddc08ffb1221d886eec398a4565bd9", hasher.hash().toString());
  }

  @Test
  public void doubleSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", 1.79769313486231570e+308));
    assertEquals("93e9a1600777d06a6c8e1d2926b7d8c6d601d4b8", hasher.hash().toString());
  }

  @Test
  public void nullSha1Hash() {
    Map<String, Object> map = new HashMap<>();
    map.put("k", null);
    JsonObjectHashing.hashJsonObject(hasher, map);
    assertEquals("29e24643a6328cb4ea893738b89c63b842ce24e7", hasher.hash().toString());
  }

  @Test
  public void arraySha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", ImmutableList.of(1L, 2L, 3L)));
    assertEquals("a1175b70883560d2b89caee549c86157048f3859", hasher.hash().toString());
  }

  @Test
  public void objectSha1Hash() {
    JsonObjectHashing.hashJsonObject(hasher, ImmutableMap.of("k", ImmutableMap.of("k2", "v2")));
    assertEquals("d19ac4d9a68ce5a15574fa3d2064084d93aac18c", hasher.hash().toString());
  }

  @Test
  public void rawShortSha1Hash() {
    // RawParser coerces integers to Long. Let's bypass it to test
    // this code path.
    Short val = 4096;
    JsonObjectHashing.hashJsonObject(hasher, val);
    assertEquals("d12109f0b8cb8b6e48f058f34766226b29d0b2bb", hasher.hash().toString());
  }

  @Test
  public void rawIntSha1Hash() {
    Integer val = 64738;
    JsonObjectHashing.hashJsonObject(hasher, val);
    assertEquals("7dbf423da794e905ef3107b7cc46bda8b7977eba", hasher.hash().toString());
  }

  @Test
  public void rawFloatSha1Hash() {
    Float val = 123.456f;
    JsonObjectHashing.hashJsonObject(hasher, val);
    assertEquals("fe1a9e33f321868d26f299524c79bf8753ab100b", hasher.hash().toString());
  }

  @Test
  public void mapOrderDoesNotChangeHash() {
    Map<?, ?> map1 = ImmutableMap.of("firstKey", 24, "secondKey", new Float(13.5));
    Map<?, ?> map2 = ImmutableMap.of("secondKey", new Float(13.5), "firstKey", 24);

    Hasher hasher1 = Hashing.sha1().newHasher();
    Hasher hasher2 = Hashing.sha1().newHasher();

    JsonObjectHashing.hashJsonObject(hasher1, map1);
    JsonObjectHashing.hashJsonObject(hasher2, map2);

    assertEquals(hasher1.hash().toString(), hasher2.hash().toString());
  }

  /**
   * This helps keep the hashes more stable. While we include the buckversion in target hashes, when
   * making changes within Buck (such as adding or removing constructor arg fields) this means that
   * there are fewer places where tests fail, meaning less time being spent debugging "failed"
   * tests.
   *
   * <p>This means that two maps may generate the same hashcode, despite not being strictly equal.
   * We rely on the use case that this class was designed for (namely, generating target hashes) to
   * ensure that correct functionality is maintained.
   */
  @Test
  public void nullFieldsAreIgnoredInTheHash() {
    HashMap<String, Object> map1 = new HashMap<>();
    map1.put("firstKey", "value");
    map1.put("secondKey", null);

    HashMap<String, Object> map2 = new HashMap<>();
    map2.put("firstKey", "value");
    map2.put("ignoredKey", null);

    Hasher hasher1 = Hashing.sha1().newHasher();
    Hasher hasher2 = Hashing.sha1().newHasher();

    JsonObjectHashing.hashJsonObject(hasher1, map1);
    JsonObjectHashing.hashJsonObject(hasher2, map2);

    assertEquals(hasher1.hash().toString(), hasher2.hash().toString());
  }
}
