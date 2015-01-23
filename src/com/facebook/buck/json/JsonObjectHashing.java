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

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hasher;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Hashes parsed JSON objects as returned by {@link RawParser}.
 */
public class JsonObjectHashing {
  private static enum HashedObjectType {
      BOOLEAN,
      DOUBLE,
      FLOAT,
      INTEGER,
      LIST,
      LONG,
      SHORT,
      STRING,
      MAP,
      NULL
  }

  // Utility class; do not instantiate.
  private JsonObjectHashing() { }

  /**
   * Given a {@link Hasher} and a parsed JSON object returned by
   * {@link RawParser#parseFromReader(Reader)}, updates the Hasher
   * with the contents of the JSON object.
   */
  public static void hashJsonObject(Hasher hasher, @Nullable Object obj) {
    if (obj instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) obj;
      ImmutableSortedMap.Builder<String, Optional<Object>> sortedMapBuilder =
          ImmutableSortedMap.naturalOrder();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Object key = entry.getKey();
        if (!(key instanceof String)) {
          throw new RuntimeException(
              String.format(
                  "Keys of JSON maps are expected to be strings. Actual type: %s, contents: %s",
                  key.getClass().getName(),
                  key));
        }
        Object value = entry.getValue();
        if (value != null) {
          sortedMapBuilder.put((String) key, Optional.of(value));
        } else {
          sortedMapBuilder.put((String) key, Optional.absent());
        }
      }
      ImmutableSortedMap<String, Optional<Object>> sortedMap = sortedMapBuilder.build();
      hasher.putInt(HashedObjectType.MAP.ordinal());
      hasher.putInt(sortedMap.size());
      for (Map.Entry<String, Optional<Object>> entry : sortedMap.entrySet()) {
        hashJsonObject(hasher, entry.getKey());
        if (entry.getValue().isPresent()) {
          hashJsonObject(hasher, entry.getValue().get());
        } else {
          hashJsonObject(hasher, null);
        }
      }
    } else if (obj instanceof Collection) {
      Collection<?> collection = (Collection<?>) obj;
      hasher.putInt(HashedObjectType.LIST.ordinal());
      hasher.putInt(collection.size());
      for (Object collectionEntry : collection) {
        hashJsonObject(hasher, collectionEntry);
      }
    } else if (obj instanceof String) {
      hasher.putInt(HashedObjectType.STRING.ordinal());
      byte[] stringBytes = ((String) obj).getBytes(Charsets.UTF_8);
      hasher.putInt(stringBytes.length);
      hasher.putBytes(stringBytes);
    } else if (obj instanceof Boolean) {
      hasher.putInt(HashedObjectType.BOOLEAN.ordinal());
      hasher.putBoolean((boolean) obj);
    } else if (obj instanceof Number) {
      // This is gross, but it mimics the logic in RawParser.
      Number number = (Number) obj;
      if (number.longValue() == number.doubleValue()) {
        hasher.putInt(HashedObjectType.LONG.ordinal());
        hasher.putLong(number.longValue());
      } else {
        hasher.putInt(HashedObjectType.DOUBLE.ordinal());
        hasher.putDouble(number.doubleValue());
      }
    } else if (obj instanceof Void || obj == null) {
      hasher.putInt(HashedObjectType.NULL.ordinal());
    } else {
      throw new RuntimeException(
          String.format("Unsupported object %s (class %s)", obj, obj.getClass()));
    }
  }
}
