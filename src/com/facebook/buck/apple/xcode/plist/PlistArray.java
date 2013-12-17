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

import com.google.common.collect.Lists;

import java.util.Iterator;
import java.util.List;

public class PlistArray extends PlistValue implements Iterable<PlistValue> {
  private List<PlistValue> value;

  public PlistArray() {
    value = Lists.newArrayList();
  }

  public void add(PlistValue item) {
    value.add(item);
  }

  @Override
  public Iterator<PlistValue> iterator() {
    return value.iterator();
  }

  @Override
  <E> E acceptVisitor(PlistVisitor<E> visitor) {
    return visitor.visit(this);
  }

  public static PlistArray of(PlistValue... values) {
    PlistArray array = new PlistArray();
    for (PlistValue value : values) {
      array.add(value);
    }
    return array;
  }
}
