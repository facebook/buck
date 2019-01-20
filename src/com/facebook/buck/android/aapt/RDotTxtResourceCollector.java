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
      RType rType, String name, Path path, DocumentLocation documentLocation) {
    RDotTxtEntry entry =
        new FakeRDotTxtEntry(IdType.INT, rType, name, RDotTxtEntry.CustomDrawableType.CUSTOM);
    if (!resources.contains(entry)) {
      addCustomResource(rType, IdType.INT, name, getNextCustomIdValue(rType));
    }
  }

  @Override
  public void addGrayscaleImageResourceIfNotPresent(
      RType rType, String name, Path path, DocumentLocation documentLocation) {
    RDotTxtEntry entry =
        new FakeRDotTxtEntry(
            IdType.INT, rType, name, RDotTxtEntry.CustomDrawableType.GRAYSCALE_IMAGE);
    if (!resources.contains(entry)) {
      addGrayscaleImageResource(rType, IdType.INT, name, getNextGrayscaleImageIdValue(rType));
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

  @Override
  public void addResourceIfNotPresent(RDotTxtEntry rDotTxtEntry) {
    if (!resources.contains(rDotTxtEntry)) {
      resources.add(rDotTxtEntry.copyWithNewIdValue(getNextIdValue(rDotTxtEntry)));
    }
  }

  public void addCustomResource(RType rType, IdType idType, String name, String idValue) {
    resources.add(
        new RDotTxtEntry(idType, rType, name, idValue, RDotTxtEntry.CustomDrawableType.CUSTOM));
  }

  public void addGrayscaleImageResource(RType rType, IdType idType, String name, String idValue) {
    resources.add(
        new RDotTxtEntry(
            idType, rType, name, idValue, RDotTxtEntry.CustomDrawableType.GRAYSCALE_IMAGE));
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

  String getNextIdValue(RDotTxtEntry rDotTxtEntry) {
    if (rDotTxtEntry.idType == IdType.INT_ARRAY) {
      return getNextArrayIdValue(rDotTxtEntry.type, rDotTxtEntry.getNumArrayValues());
    } else if (rDotTxtEntry.type == RType.STYLEABLE) {
      // styleable int entries are just incremented ints that receive a value when created as
      // siblings of a style (non unique within R.txt)
      return rDotTxtEntry.idValue;
    } else if (rDotTxtEntry.customType == RDotTxtEntry.CustomDrawableType.CUSTOM) {
      return getNextCustomIdValue(rDotTxtEntry.type);
    } else if (rDotTxtEntry.customType == RDotTxtEntry.CustomDrawableType.GRAYSCALE_IMAGE) {
      return getNextGrayscaleImageIdValue(rDotTxtEntry.type);
    } else {
      return getNextIdValue(rDotTxtEntry.type);
    }
  }

  String getNextIdValue(RType rType) {
    return String.format("0x%08x", getEnumerator(rType).next());
  }

  String getNextCustomIdValue(RType rType) {
    return String.format(
        "0x%08x %s", getEnumerator(rType).next(), RDotTxtEntry.CUSTOM_DRAWABLE_IDENTIFIER);
  }

  String getNextGrayscaleImageIdValue(RType rType) {
    return String.format(
        "0x%08x %s", getEnumerator(rType).next(), RDotTxtEntry.GRAYSCALE_IMAGE_IDENTIFIER);
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
