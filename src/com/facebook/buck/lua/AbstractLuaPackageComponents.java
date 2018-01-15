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

package com.facebook.buck.lua;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.immutables.value.Value;

/** Components that contribute to a Lua package. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractLuaPackageComponents implements AddsToRuleKey {

  /** @return mapping of module names to their respective {@link SourcePath}s. */
  @AddToRuleKey
  @Value.NaturalOrder
  public abstract ImmutableSortedMap<String, SourcePath> getModules();

  /** @return mapping of python module names to their respective {@link SourcePath}s. */
  @AddToRuleKey
  @Value.NaturalOrder
  public abstract ImmutableSortedMap<String, SourcePath> getPythonModules();

  /** @return a mapping of shared native library names to their respective {@link SourcePath}s. */
  @AddToRuleKey
  @Value.NaturalOrder
  public abstract ImmutableSortedMap<String, SourcePath> getNativeLibraries();

  public static void addComponents(
      LuaPackageComponents.Builder builder, LuaPackageComponents components) {
    builder.putAllModules(components.getModules());
    builder.putAllPythonModules(components.getPythonModules());
    builder.putAllNativeLibraries(components.getNativeLibraries());
  }

  public ImmutableSortedSet<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(ruleFinder.filterBuildRuleInputs(getModules().values()))
        .addAll(ruleFinder.filterBuildRuleInputs(getPythonModules().values()))
        .addAll(ruleFinder.filterBuildRuleInputs(getNativeLibraries().values()))
        .build();
  }

  /** @return whether any components may be prebuilt native libraries. */
  public boolean hasNativeCode(CxxPlatform cxxPlatform) {
    for (String module : getModules().keySet()) {
      if (module.endsWith(cxxPlatform.getSharedLibraryExtension())) {
        return true;
      }
    }
    for (String module : getPythonModules().keySet()) {
      if (module.endsWith(cxxPlatform.getSharedLibraryExtension())) {
        return true;
      }
    }
    return false;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private final Map<String, SourcePath> modules = new LinkedHashMap<>();
    private final Map<String, SourcePath> pythonModules = new LinkedHashMap<>();
    private final Map<String, SourcePath> nativeLibraries = new LinkedHashMap<>();

    public Builder putModules(String name, SourcePath path) {
      SourcePath existing = modules.get(name);
      if (existing != null && !existing.equals(path)) {
        throw new HumanReadableException(
            "conflicting modules for %s: %s != %s", name, path, existing);
      }
      modules.put(name, path);
      return this;
    }

    public Builder putAllModules(Map<String, SourcePath> modules) {
      for (Map.Entry<String, SourcePath> entry : modules.entrySet()) {
        putModules(entry.getKey(), entry.getValue());
      }
      return this;
    }

    public Builder putPythonModules(String name, SourcePath path) {
      SourcePath existing = pythonModules.get(name);
      if (existing != null && !existing.equals(path)) {
        throw new HumanReadableException(
            "conflicting python modules for %s: %s != %s", name, path, existing);
      }
      pythonModules.put(name, path);
      return this;
    }

    public Builder putAllPythonModules(Map<String, SourcePath> modules) {
      for (Map.Entry<String, SourcePath> entry : modules.entrySet()) {
        putPythonModules(entry.getKey(), entry.getValue());
      }
      return this;
    }

    public Builder putNativeLibraries(String name, SourcePath path) {
      SourcePath existing = nativeLibraries.get(name);
      if (existing != null && !existing.equals(path)) {
        throw new HumanReadableException(
            "conflicting native libraries for %s: %s != %s", name, path, existing);
      }
      nativeLibraries.put(name, path);
      return this;
    }

    public Builder putAllNativeLibraries(Map<String, SourcePath> nativeLibraries) {
      for (Map.Entry<String, SourcePath> entry : nativeLibraries.entrySet()) {
        putNativeLibraries(entry.getKey(), entry.getValue());
      }
      return this;
    }

    public LuaPackageComponents build() {
      return LuaPackageComponents.of(modules, pythonModules, nativeLibraries);
    }
  }
}
