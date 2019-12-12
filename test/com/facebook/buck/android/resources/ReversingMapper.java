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

package com.facebook.buck.android.resources;

import java.nio.IntBuffer;

class ReversingMapper implements ReferenceMapper {
  private ResourceTable resourceTable;

  public ReversingMapper(ResourceTable resourceTable) {
    this.resourceTable = resourceTable;
  }

  public static ReferenceMapper construct(ResourceTable resourceTable) {
    return new ReversingMapper(resourceTable);
  }

  @Override
  public int map(int id) {
    int resPackage = id >> 24;
    if (resPackage != ResTablePackage.APP_PACKAGE_ID) {
      return id;
    }
    int resType = (id >> 16) & 0xFF;
    int resId = id & 0xFFFF;
    int entryCount = resourceTable.getPackage().getTypeSpec(resType).getEntryCount();
    return (id & 0xFFFF0000) | (entryCount - resId - 1);
  }

  @Override
  public void rewrite(int type, IntBuffer buf) {
    for (int i = 0; i < buf.limit() / 2; i++) {
      int o = buf.limit() - i - 1;
      int t = buf.get(i);
      buf.put(i, buf.get(o));
      buf.put(o, t);
    }
  }
}
