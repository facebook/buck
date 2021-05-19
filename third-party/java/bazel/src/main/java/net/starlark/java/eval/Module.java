/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
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

// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.syntax.ResolverModule;

/**
 * A {@link Module} represents a Starlark module, a container of global variables populated by
 * executing a Starlark file. Each top-level assignment updates a global variable in the module.
 *
 * <p>Each module references its "predeclared" environment, which is often shared among many
 * modules. These are the names that are defined even at the start of execution. For example, in
 * Bazel, the predeclared environment of the module for a BUILD or .bzl file defines name values
 * such as cc_binary and glob.
 *
 * <p>The predeclared environment implicitly includes the "universal" names present in every
 * Starlark thread in every dialect, such as None, len, and str; see {@link Starlark#UNIVERSE}.
 *
 * <p>Global bindings in a Module may shadow bindings inherited from the predeclared block.
 *
 * <p>A module may carry an arbitrary piece of client data. In Bazel, for example, the client data
 * records the module's build label (such as "//dir:file.bzl").
 *
 * <p>Use {@link #create} to create a {@link Module} with no predeclared bindings other than the
 * universal ones. Use {@link #withPredeclared(Map)} to create a module with the predeclared
 * environment specified by the map, using the semantics to determine whether any FlagGuardedValues
 * in the map are enabled or disabled.
 */
public final class Module {

  private final ResolverModule resolverModule;
  private Object[] globals;

  public Module(ResolverModule resolverModule) {
    this.resolverModule = resolverModule;
    this.globals = new Object[resolverModule.globalIndexSize()];
  }

  public static Module create() {
    return withPredeclared(ImmutableMap.of());
  }

  /**
   * Constructs a Module with the specified predeclared bindings, filtered by the semantics, in
   * addition to the standard environment, {@link Starlark#UNIVERSE}.
   */
  public static Module withPredeclared(ImmutableMap<String, Object> predeclared) {
    return new Module(new ResolverModule(predeclared, Starlark.UNIVERSE_OBJECTS));
  }

  public ResolverModule getResolverModule() {
    return resolverModule;
  }

  /** Allocate globals if {@link ResolverModule} was modified after this object created. */
  public void allocateGlobalsAfterResolution() {
    if (globals.length < resolverModule.globalIndexSize()) {
      globals = Arrays.copyOf(globals, resolverModule.globalIndexSize());
    }
  }

  // An optional piece of metadata associated with the module/file.
  // May be set after construction (too obscure to burden the constructors).
  // Its toString appears to Starlark in str(function): "<function f from ...>".
  @Nullable private Object clientData;

  /**
   * Returns a map in which each semantics-enabled FlagGuardedValue has been replaced by the value
   * it guards. Disabled FlagGuardedValues are left in place, and should be treated as unavailable.
   * The iteration order is unchanged.
   */
  private static ImmutableMap<String, Object> filter(Map<String, Object> predeclared) {
    ImmutableMap.Builder<String, Object> filtered = ImmutableMap.builder();
    for (Map.Entry<String, Object> bind : predeclared.entrySet()) {
      Object v = bind.getValue();
      filtered.put(bind.getKey(), v);
    }
    return filtered.build();
  }

  /**
   * Sets the client data (an arbitrary application-specific value) associated with the module. It
   * may be retrieved using {@link #getClientData}. Its {@code toString} form appears in the result
   * of {@code str(fn)} where {@code fn} is a StarlarkFunction: "<function f from ...>".
   */
  public void setClientData(@Nullable Object clientData) {
    this.clientData = clientData;
  }

  /**
   * Returns the client data associated with this module by a prior call to {@link #setClientData}.
   */
  @Nullable
  public Object getClientData() {
    return clientData;
  }

  /**
   * Returns an immutable mapping containing the global variables of this module.
   *
   * <p>The bindings are returned in a deterministic order (for a given sequence of initial values
   * and updates).
   */
  public ImmutableMap<String, Object> getGlobals() {
    Map<String, Integer> index = resolverModule.getIndex();
    int n = index.size();
    ImmutableMap.Builder<String, Object> m = ImmutableMap.builderWithExpectedSize(n);
    for (Map.Entry<String, Integer> e : index.entrySet()) {
      Object v = getGlobalByIndex(e.getValue());
      if (v != null) {
        m.put(e.getKey(), v);
      }
    }
    return m.build();
  }

  /**
   * Returns the value of the specified global variable, or null if not bound. Does not look in the
   * predeclared environment.
   */
  public Object getGlobal(String name) {
    Integer i = resolverModule.getIndexOfGlobalOrNull(name);
    return i != null ? globals[i] : null;
  }

  /**
   * Sets the value of a global variable based on its index in this module ({@see
   * getIndexOfGlobal}).
   */
  void setGlobalByIndex(int i, Object v) {
    Preconditions.checkArgument(i < resolverModule.globalIndexSize());
    this.globals[i] = v;
  }

  /**
   * Returns the value of a global variable based on its index in this module ({@see
   * getIndexOfGlobal}.) Returns null if the variable has not been assigned a value.
   */
  @Nullable
  Object getGlobalByIndex(int i) {
    Preconditions.checkArgument(i < resolverModule.globalIndexSize());
    return this.globals[i];
  }

  /** Updates a global binding in the module environment. */
  public void setGlobal(String name, Object value) {
    Preconditions.checkNotNull(value, "Module.setGlobal(%s, null)", name);

    int indexOfGlobal = resolverModule.getIndexOfGlobal(name);
    if (globals.length <= indexOfGlobal) {
      globals = Arrays.copyOf(globals, Math.max(indexOfGlobal + 1, globals.length << 1));
    }
    setGlobalByIndex(indexOfGlobal, value);
  }

  @Override
  public String toString() {
    return String.format("<module %s>", clientData == null ? "?" : clientData);
  }
}
