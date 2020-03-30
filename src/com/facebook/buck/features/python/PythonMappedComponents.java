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

package com.facebook.buck.features.python;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.impl.SymlinkMap;
import com.facebook.buck.core.rules.impl.Symlinks;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.step.fs.SymlinkPaths;
import com.facebook.buck.util.MoreMaps;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.immutables.value.Value;

/**
 * An implementation of {@link PythonComponents} wrapping a fixed map of sources, where the keys
 * determine where in the Python package the sources get added.
 */
@BuckStyleValue
public abstract class PythonMappedComponents implements PythonComponents {

  public static PythonMappedComponents of(ImmutableSortedMap<Path, SourcePath> components) {
    return ImmutablePythonMappedComponents.of(components);
  }

  // TODO(agallagher): The keys here should be module/resource names in `String` form (e.g,
  //  `foo.bar`) -- not `Path`s (which requires the hack below).
  public abstract ImmutableSortedMap<Path, SourcePath> getComponents();

  // NOTE(agallagher): When rule key hashing sees the `Path` objects in the above map, it wants to
  //  include the hash of their contents on disk into the rule keys, which is wrong as they don't
  //  exist on the filesystem (they're relative locations in the final Python package) (and even if
  //  they did exist, we don't care about their contents).  To work around this, we have a special
  //  helper here to convert to string form at rule-key-calc time.
  @AddToRuleKey
  @Value.Derived
  @Value.Auxiliary
  public Supplier<ImmutableSortedMap<String, SourcePath>> getComponentsRuleKey() {
    return () -> MoreMaps.transformKeysAndSort(getComponents(), Path::toString);
  }

  @Override
  public void forEachInput(Consumer<SourcePath> consumer) {
    getComponents().values().forEach(consumer);
  }

  @Override
  public Resolved resolvePythonComponents(SourcePathResolverAdapter resolver) {
    return new Resolved(resolver.getMappedPaths(getComponents()));
  }

  /**
   * An implementation of {@link com.facebook.buck.features.python.PythonComponents.Resolved} for
   * {@link PythonMappedComponents} with {@link SourcePath}s resolved to {@link Path}s for use with
   * {@link com.facebook.buck.step.Step}.
   */
  public static class Resolved implements PythonComponents.Resolved {

    private final ImmutableMap<Path, Path> resolved;

    public Resolved(ImmutableMap<Path, Path> resolved) {
      this.resolved = resolved;
    }

    @Override
    public void forEachPythonComponent(ComponentConsumer consumer) throws IOException {
      for (Map.Entry<Path, Path> ent : resolved.entrySet()) {
        consumer.accept(ent.getKey(), ent.getValue());
      }
    }
  }

  // Use a `Lazy` annotation here so that we get a single instance that multiple different top-level
  // Python binaries can re-use to get rule key caching.
  @Override
  @Value.Lazy
  public Symlinks asSymlinks() {
    return new SymlinkMap(getComponents()) {
      @Override
      public SymlinkPaths resolveSymlinkPaths(SourcePathResolverAdapter resolver) {
        // Don't support resolving to `SymlinkPath`s for individual component objects, as we rely on
        // on an implementation which combines all component objects in a package to provide module
        // conflict detection.
        throw new UnsupportedOperationException();
      }
    };
  }
}
