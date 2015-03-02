/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android.aapt;

import static com.google.common.base.Preconditions.checkNotNull;

import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Responsible for collecting resources parsed by {@link MiniAapt} and assigning unique integer ids
 * to those resources. Resource ids are of the type {@code 0x7fxxyyyy}, where  {@code xx} represents
 * the resource type, and {@code yyyy} represents the id within that resource type.
 */
public class AaptResourceCollector {

  private int currentTypeId;
  private final Map<RType, ResourceIdEnumerator> enumerators;
  private final Set<RDotTxtEntry> resources;

  public AaptResourceCollector() {
    this.enumerators = Maps.newHashMap();
    this.resources = Sets.newHashSet();
    this.currentTypeId = 1;
  }

  public void addIntResourceIfNotPresent(RType rType, String name) {
    if (!enumerators.containsKey(rType)) {
      enumerators.put(rType, new ResourceIdEnumerator(currentTypeId++));
    }

    RDotTxtEntry entry = new FakeRDotTxtEntry(IdType.INT, rType, name);
    if (!resources.contains(entry)) {
      String idValue = String.format("0x%08x", checkNotNull(enumerators.get(rType)).next());
      addResource(rType, IdType.INT, name, idValue);
    }
  }

  public void addIntArrayResourceIfNotPresent(RType rType, String name, int numValues) {
    // Robolectric expects the array to be populated with the right number of values, irrespective
    // of what the values are.
    String idValue = String.format(
        "{ %s }",
        Joiner.on(",").join(Collections.nCopies(numValues, "0x7f000000")));
    addResource(rType, IdType.INT_ARRAY, name, idValue);
  }

  public void addResource(RType rType, IdType idType, String name, String idValue) {
    resources.add(new RDotTxtEntry(idType, rType, name, idValue));
  }

  public Set<RDotTxtEntry> getResources() {
    return Collections.unmodifiableSet(resources);
  }

  private static class ResourceIdEnumerator {

    private int currentId;

    ResourceIdEnumerator(int typeId) {
      this.currentId = 0x7f000000 + 0x10000 * typeId + 1;
    }

    int next() {
      return currentId++;
    }
  }
}
