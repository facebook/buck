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

package com.facebook.buck.apple.xcode.plist;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.util.Iterator;
import java.util.Map;

public class PlistDictionary extends PlistValue implements Iterable<Map.Entry<String, PlistValue>> {
  private Map<String, PlistValue> value;

  public PlistDictionary() {
    this.value = Maps.newHashMap();
  }

  public PlistValue get(String key) {
    return this.value.get(key);
  }

  public void put(String key, PlistValue value) {
    this.value.put(key, value);
  }

  @Override
  <E> E acceptVisitor(PlistVisitor<E> visitor) {
    return visitor.visit(this);
  }

  @Override
  public Iterator<Map.Entry<String, PlistValue>> iterator() {
    return value.entrySet().iterator();
  }

  public ImmutableMap<String, PlistValue> asMap() {
    return ImmutableMap.copyOf(value);
  }

  public static PlistDictionary of(String k, PlistValue v) {
    PlistDictionary dictionary = new PlistDictionary();
    dictionary.put(k, v);
    return dictionary;
  }
}
