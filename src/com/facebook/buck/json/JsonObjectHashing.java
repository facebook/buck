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

import com.facebook.buck.util.hashing.StringHashing;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hasher;
import com.google.devtools.build.lib.syntax.SelectorList;
import com.google.devtools.build.lib.syntax.SelectorValue;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** Hashes parsed BUCK file objects. */
public class JsonObjectHashing {
  private enum HashedObjectType {
    BOOLEAN,
    DOUBLE,
    FLOAT,
    INTEGER,
    LIST,
    LONG,
    SHORT,
    STRING,
    MAP,
    SELECTOR_LIST,
    SELECTOR_VALUE,
    NULL,
  }

  // Utility class; do not instantiate.
  private JsonObjectHashing() {}

  /**
   * Given a {@link Hasher} and a parsed BUCK file object, updates the Hasher with the contents of
   * the JSON object.
   */
  public static void hashJsonObject(Hasher hasher, @Nullable Object obj) {
    if (obj instanceof SkylarkNestedSet) {
      obj = ((SkylarkNestedSet) obj).toCollection();
    }
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
                  key.getClass().getName(), key));
        }
        Object value = entry.getValue();
        if (value != null) {
          sortedMapBuilder.put((String) key, Optional.of(value));
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
      String s = (String) obj;
      StringHashing.hashStringAndLength(hasher, s);
    } else if (obj instanceof Boolean) {
      hasher.putInt(HashedObjectType.BOOLEAN.ordinal());
      hasher.putBoolean((boolean) obj);
    } else if (obj instanceof Number) {
      // This is gross, but it mimics the logic originally in RawParser.
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
    } else if (obj instanceof SelectorList) {
      SelectorList selectorList = (SelectorList) obj;
      hasher.putInt(HashedObjectType.SELECTOR_LIST.ordinal());
      List<Object> elements = selectorList.getElements();
      hasher.putInt(elements.size());
      for (Object collectionEntry : elements) {
        hashJsonObject(hasher, collectionEntry);
      }
    } else if (obj instanceof SelectorValue) {
      SelectorValue selectorValue = (SelectorValue) obj;
      hasher.putInt(HashedObjectType.SELECTOR_VALUE.ordinal());
      hashJsonObject(hasher, selectorValue.getDictionary());
      hashJsonObject(hasher, selectorValue.getNoMatchError());
    } else {
      throw new RuntimeException(
          String.format("Unsupported object %s (class %s)", obj, obj.getClass()));
    }
  }
}
