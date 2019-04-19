/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.command.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.util.MoreMaps;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Map;
import org.immutables.value.Value;

/**
 * Contains configuration options that are not affecting daemon state and not causing the daemon to
 * restart.
 */
@BuckStyleTuple
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractConfigIgnoredByDaemon implements ConfigView<BuckConfig> {

  @Override
  public abstract BuckConfig getDelegate();

  private static ImmutableMap<String, ImmutableSet<String>> getIgnoreFieldsForDaemonRestart() {
    ImmutableMap.Builder<String, ImmutableSet<String>> ignoreFieldsForDaemonRestartBuilder =
        ImmutableMap.builder();
    ignoreFieldsForDaemonRestartBuilder.put(
        "apple", ImmutableSet.of("generate_header_symlink_tree_only"));
    ignoreFieldsForDaemonRestartBuilder.put("build", ImmutableSet.of("threads"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "cache",
        ImmutableSet.of("dir", "dir_mode", "http_mode", "http_url", "mode", "slb_server_pool"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "client", ImmutableSet.of("id", "skip-action-graph-cache"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "intellij", ImmutableSet.of("multi_cell_module_support"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "log",
        ImmutableSet.of(
            "chrome_trace_generation",
            "compress_traces",
            "max_traces",
            "public_announcements",
            "log_build_id_to_console_enabled",
            "build_details_template",
            "build_details_commands"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "project", ImmutableSet.of("ide_prompt", "ide_force_kill"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "ui",
        ImmutableSet.of(
            "superconsole",
            "thread_line_limit",
            "thread_line_output_max_columns",
            "warn_on_config_file_overrides",
            "warn_on_config_file_overrides_ignored_files"));
    ignoreFieldsForDaemonRestartBuilder.put("color", ImmutableSet.of("ui"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "version_control", ImmutableSet.of("generate_statistics"));
    return ignoreFieldsForDaemonRestartBuilder.build();
  }

  @Value.Lazy
  public ImmutableMap<String, ImmutableMap<String, String>> getRawConfigForParser() {
    ImmutableMap<String, ImmutableSet<String>> ignoredFields = getIgnoreFieldsForDaemonRestart();
    ImmutableMap<String, ImmutableMap<String, String>> rawSections =
        getDelegate().getConfig().getSectionToEntries();

    // If the raw config doesn't have sections which have ignored fields, then just return it as-is.
    ImmutableSet<String> sectionsWithIgnoredFields = ignoredFields.keySet();
    if (Sets.intersection(rawSections.keySet(), sectionsWithIgnoredFields).isEmpty()) {
      return rawSections;
    }

    // Otherwise, iterate through the config to do finer-grain filtering.
    ImmutableMap.Builder<String, ImmutableMap<String, String>> filtered = ImmutableMap.builder();
    for (Map.Entry<String, ImmutableMap<String, String>> sectionEnt : rawSections.entrySet()) {
      String sectionName = sectionEnt.getKey();

      // If this section doesn't have a corresponding ignored section, then just add it as-is.
      if (!sectionsWithIgnoredFields.contains(sectionName)) {
        filtered.put(sectionEnt);
        continue;
      }

      // If none of this section's entries are ignored, then add it as-is.
      ImmutableMap<String, String> fields = sectionEnt.getValue();
      ImmutableSet<String> ignoredFieldNames =
          ignoredFields.getOrDefault(sectionName, ImmutableSet.of());
      if (Sets.intersection(fields.keySet(), ignoredFieldNames).isEmpty()) {
        filtered.put(sectionEnt);
        continue;
      }

      // Otherwise, filter out the ignored fields.
      ImmutableMap<String, String> remainingKeys =
          ImmutableMap.copyOf(Maps.filterKeys(fields, Predicates.not(ignoredFieldNames::contains)));
      filtered.put(sectionName, remainingKeys);
    }

    return MoreMaps.filterValues(filtered.build(), Predicates.not(Map::isEmpty));
  }
}
