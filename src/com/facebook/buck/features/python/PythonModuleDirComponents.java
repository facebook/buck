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
import com.facebook.buck.core.rules.impl.SymlinkDir;
import com.facebook.buck.core.rules.impl.Symlinks;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.step.fs.SymlinkPaths;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/**
 * A {@link PythonComponents} which wraps a directory containing components to add to a top-level
 * Python binary.
 */
// TODO(agallagher): This directory can contain more than just "modules" (e.g. "resources"), so it'd
//  be nice to find a way to handle this properly.
@BuckStyleValue
abstract class PythonModuleDirComponents implements PythonComponents {

  public static PythonModuleDirComponents of(SourcePath directory) {
    return ImmutablePythonModuleDirComponents.of(directory);
  }

  // TODO(agallagher): We hash the entire dir contents even though, when creating symlinks, we only
  //  actually really care about the directory structure.  Ideally, we'd have a way to model this
  //  via rule key hashing to avoid unnecessary rebuilds.
  @AddToRuleKey
  abstract SourcePath getDirectory();

  @Override
  public void forEachInput(Consumer<SourcePath> consumer) {
    consumer.accept(getDirectory());
  }

  @Override
  public Resolved resolvePythonComponents(SourcePathResolverAdapter resolver) {
    return new Resolved(resolver.getAbsolutePath(getDirectory()));
  }

  /**
   * An implementation of {@link com.facebook.buck.features.python.PythonComponents.Resolved} for
   * {@link PythonModuleDirComponents} with {@link SourcePath}s resolved to {@link Path}s for use
   * with {@link com.facebook.buck.step.Step}.
   */
  static class Resolved implements PythonComponents.Resolved {
    private final Path directory;

    public Resolved(Path directory) {
      Preconditions.checkArgument(directory.isAbsolute());
      this.directory = directory;
    }

    @Override
    public void forEachPythonComponent(ComponentConsumer consumer) throws IOException {
      Files.walkFileTree(
          directory,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              consumer.accept(directory.relativize(file), file);
              return super.visitFile(file, attrs);
            }
          });
    }
  }

  @Override
  public Symlinks asSymlinks() {
    return new SymlinkDir(getDirectory()) {
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
