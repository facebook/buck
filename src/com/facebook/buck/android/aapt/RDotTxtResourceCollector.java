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

package com.facebook.buck.android.aapt;

import com.facebook.buck.android.aapt.RDotTxtEntry.CustomDrawableType;
import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.util.xml.DocumentLocation;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Responsible for collecting resources parsed by {@link MiniAapt} and assigning unique integer ids
 * to those resources. Resource ids are of the type {@code 0x7fxxyyyy}, where {@code xx} represents
 * the resource type, and {@code yyyy} represents the id within that resource type.
 */
public class RDotTxtResourceCollector implements ResourceCollector {

  private int currentTypeId;
  private final Map<RType, ResourceIdEnumerator> enumerators;
  private final Set<RDotTxtEntry> resources;

  public RDotTxtResourceCollector() {
    this.enumerators = new HashMap<>();
    this.resources = new HashSet<>();
    this.currentTypeId = 1;
  }

  @Override
  public void addIntResourceIfNotPresent(
      RType rType, String name, Path path, DocumentLocation documentLocation) {
    RDotTxtEntry entry = new FakeRDotTxtEntry(IdType.INT, rType, name);
    if (!resources.contains(entry)) {
      addResource(rType, IdType.INT, name, getNextIdValue(rType), null, path, documentLocation);
    }
  }

  @Override
  public void addCustomDrawableResourceIfNotPresent(
      RType rType,
      String name,
      Path path,
      DocumentLocation documentLocation,
      CustomDrawableType drawableType) {
    RDotTxtEntry entry = new FakeRDotTxtEntry(IdType.INT, rType, name);
    if (!resources.contains(entry)) {
      String idValue = getNextCustomIdValue(rType, drawableType);
      resources.add(new RDotTxtEntry(IdType.INT, rType, name, idValue, drawableType));
    }
  }

  @Override
  public void addIntArrayResourceIfNotPresent(
      RType rType, String name, int numValues, Path path, DocumentLocation documentLocation) {
    addResource(
        rType,
        IdType.INT_ARRAY,
        name,
        getNextArrayIdValue(rType, numValues),
        null,
        path,
        documentLocation);
  }

  @Override
  public void addResource(
      RType rType,
      IdType idType,
      String name,
      String idValue,
      @Nullable String parent,
      Path path,
      DocumentLocation documentLocation) {
    resources.add(new RDotTxtEntry(idType, rType, name, idValue, parent));
  }

  public Set<RDotTxtEntry> getResources() {
    return Collections.unmodifiableSet(resources);
  }

  ResourceIdEnumerator getEnumerator(RType rType) {
    if (!enumerators.containsKey(rType)) {
      enumerators.put(rType, new ResourceIdEnumerator(currentTypeId++));
    }
    return Objects.requireNonNull(enumerators.get(rType));
  }

  String getNextIdValue(RType rType) {
    return String.format("0x%08x", getEnumerator(rType).next());
  }

  String getNextCustomIdValue(RType rType, CustomDrawableType drawableType) {
    return String.format("0x%08x %s", getEnumerator(rType).next(), drawableType.getIdentifier());
  }

  String getNextArrayIdValue(RType rType, int numValues) {
    // Robolectric expects the array to be populated with the right number of values, irrespective
    // of what the values are.
    ImmutableList.Builder<String> values = ImmutableList.builder();
    for (int id = 0; id < numValues; id++) {
      values.add(String.format("0x%x", getEnumerator(rType).next()));
    }

    return String.format(
        "{ %s }", Joiner.on(RDotTxtEntry.INT_ARRAY_SEPARATOR).join(values.build()));
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
