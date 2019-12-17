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

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.Symlinks;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Interface representing the modules, resources, etc. that dependencies contribute to Python
 * binaries, used to model how these components should be handled by {@link BuildRule}s (e.g. how
 * they're hashed into rule keys).
 */
public interface PythonComponents extends AddsToRuleKey {

  /** Run {@code consumer} on all {@link SourcePath}s contained in this object. */
  void forEachInput(Consumer<SourcePath> consumer);

  /**
   * Convert this {@link PythonComponents} to a {@link Resolved}, where all {@link
   * com.facebook.buck.core.sourcepath.SourcePath}s have been resolved to {@link Path}s for use with
   * {@link com.facebook.buck.step.Step}s.
   */
  Resolved resolvePythonComponents(SourcePathResolverAdapter resolver);

  /**
   * Resolve this {@link PythonComponents} into a class usable by {@link
   * com.facebook.buck.step.Step}s.
   */
  interface Resolved {

    /**
     * Called by executing {@link com.facebook.buck.step.Step}s to iterate over the components owned
     * by this {@link PythonComponents}.
     */
    void forEachPythonComponent(ComponentConsumer consumer) throws IOException;

    /**
     * A {@link java.util.function.BiConsumer} which throws a {@link IOException} for use by
     * executing {@link com.facebook.buck.step.Step}s to process the {@link Path}s to the components
     * from this object.
     */
    interface ComponentConsumer {

      /**
       * @param destination this components location in the Python package.
       * @param source this components source location on disk.
       */
      void accept(Path destination, Path source) throws IOException;
    }
  }

  /**
   * @return a {@link Symlinks} used to symlink the components contained in this object via a {@link
   *     com.facebook.buck.core.rules.impl.SymlinkTree} rule.
   */
  Symlinks asSymlinks();
}
