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

package com.facebook.buck.util;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structured representation of data read from a stack of {@code .ini} files, where each file can
 * override values defined by the previous ones.
 */
public class Config {

  private final ImmutableMap<String, ImmutableMap<String, String>> sectionToEntries;

  public Config() {
    this(ImmutableMap.<String, ImmutableMap<String, String>>of());
  }

  @SafeVarargs
  public Config(ImmutableMap<String, ImmutableMap<String, String>>... maps) {
    this(ImmutableList.copyOf(maps));
  }

  public Config(
      ImmutableList<ImmutableMap<String, ImmutableMap<String, String>>> sectionToEntries) {
    this(sectionToEntriesFromMaps(sectionToEntries));
  }

  public Config(ImmutableMap<String, ImmutableMap<String, String>> sectionToEntries) {
    this.sectionToEntries = sectionToEntries;
  }

  public ImmutableMap<String, ImmutableMap<String, String>> getSectionToEntries() {
    return sectionToEntries;
  }

  public ImmutableMap<String, String> get(String sectionName) {
    return Optional
        .fromNullable(sectionToEntries.get(sectionName))
        .or(ImmutableMap.<String, String>of());
  }

  /**
   * ini4j leaves things that look like comments in the values of entries in the file. Generally,
   * we don't want to include these in our parameters, so filter them out where necessary. In an INI
   * file, the comment separator is ";", but some parsers (ini4j included) use "#" too. This method
   * handles both cases.
   *
   * @return An {@link ImmutableList} containing all entries that don't look like comments, or the
   *     empty list if there are no values.
   */
  public ImmutableList<String> getListWithoutComments(String sectionName, String propertyName) {
    Optional<String> value = getValue(sectionName, propertyName);
    if (!value.isPresent()) {
      return ImmutableList.of();
    }

    Iterable<String> allValues = Splitter.on(',')
        .omitEmptyStrings()
        .trimResults()
        .split(value.get());
    return FluentIterable.from(allValues)
        .filter(
            new Predicate<String>() {
              @Override
              public boolean apply(String input) {
                // Reject if the first printable character is an ini comment char (';' or '#')
                return !Pattern.compile("^\\s*[#;]").matcher(input).find();
              }
            })
        .toList();
  }

  public Optional<String> getValue(String sectionName, String propertyName) {
    ImmutableMap<String, String> properties = get(sectionName);
    return Optional.fromNullable(properties.get(propertyName));
  }

  public Optional<Long> getLong(String sectionName, String propertyName) {
    Optional<String> value = getValue(sectionName, propertyName);
    return value.isPresent() ?
        Optional.of(Long.valueOf(value.get())) :
        Optional.<Long>absent();
  }

  public boolean getBooleanValue(String sectionName, String propertyName, boolean defaultValue) {
    Map<String, String> entries = get(sectionName);
    if (!entries.containsKey(propertyName)) {
      return defaultValue;
    }

    String answer = Preconditions.checkNotNull(entries.get(propertyName));
    switch (answer.toLowerCase()) {
      case "yes":
      case "true":
        return true;

      case "no":
      case "false":
        return false;

      default:
        throw new HumanReadableException(
            "Unknown value for %s in [%s]: %s; should be yes/no true/false!",
            propertyName,
            sectionName);
    }
  }

  public <T extends Enum<T>> Optional<T> getEnum(String section, String field, Class<T> clazz) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.absent();
    }
    try {
      return Optional.of(Enum.valueOf(clazz, value.get().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException(
          ".buckconfig: %s:%s must be one of %s (was %s)",
          section,
          field,
          clazz.getEnumConstants(),
          value.get());
    }
  }

  private static ImmutableMap<String, ImmutableMap<String, String>> sectionToEntriesFromMaps(
      ImmutableList<ImmutableMap<String, ImmutableMap<String, String>>> maps) {
    Map<String, Map<String, String>> sectionToEntries = new LinkedHashMap<>();
    for (ImmutableMap<String, ImmutableMap<String, String>> map : maps) {
      for (Map.Entry<String, ImmutableMap<String, String>> section : map.entrySet()) {
        if (!sectionToEntries.containsKey(section.getKey())) {
          sectionToEntries.put(section.getKey(), new LinkedHashMap<String, String>());
        }
        Map<String, String> entries = Preconditions.checkNotNull(
            sectionToEntries.get(section.getKey()));
        for (Map.Entry<String, String> entry : section.getValue().entrySet()) {
          entries.put(entry.getKey(), entry.getValue());
        }
      }
    }
    ImmutableMap.Builder<String, ImmutableMap<String, String>> builder = ImmutableMap.builder();
    for (Map.Entry<String, Map<String, String>> entry : sectionToEntries.entrySet()) {
      builder.put(entry.getKey(), ImmutableMap.copyOf(entry.getValue()));
    }
    return builder.build();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (!(obj instanceof Config)) {
      return false;
    }
    Config that = (Config) obj;
    return Objects.equal(this.sectionToEntries, that.sectionToEntries);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(sectionToEntries);
  }

}
