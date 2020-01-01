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
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.step.fs.SymlinkDirPaths;
import com.facebook.buck.step.fs.SymlinkPaths;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;
import org.immutables.value.Value;

/** {@link PythonComponents} contributed by {@link PrebuiltPythonLibrary} rules. */
@BuckStyleValue
abstract class PrebuiltPythonLibraryComponents implements PythonComponents {

  protected abstract Type getType();

  @AddToRuleKey
  abstract SourcePath getDirectory();

  public static PrebuiltPythonLibraryComponents ofModules(SourcePath sourcePath) {
    return ImmutablePrebuiltPythonLibraryComponents.of(Type.MODULES, sourcePath);
  }

  public static PrebuiltPythonLibraryComponents ofResources(SourcePath sourcePath) {
    return ImmutablePrebuiltPythonLibraryComponents.of(Type.RESOURCES, sourcePath);
  }

  public static PrebuiltPythonLibraryComponents ofSources(SourcePath sourcePath) {
    return ImmutablePrebuiltPythonLibraryComponents.of(Type.SOURCES, sourcePath);
  }

  private static boolean accept(Type type, Path path) {
    String extension = MorePaths.getFileExtension(path);
    switch (type) {
      case MODULES:
        return extension.equals(PythonUtil.SOURCE_EXT)
            || extension.equals(PythonUtil.NATIVE_EXTENSION_EXT);
      case SOURCES:
        return extension.equals(PythonUtil.SOURCE_EXT);
      case RESOURCES:
        return !extension.equals(PythonUtil.SOURCE_EXT)
            && !extension.equals(PythonUtil.NATIVE_EXTENSION_EXT);
    }
    throw new IllegalStateException();
  }

  @Override
  public void forEachInput(Consumer<SourcePath> consumer) {
    consumer.accept(getDirectory());
  }

  @Override
  @Value.Lazy
  public Symlinks asSymlinks() {
    return new SymlinkDir(getDirectory()) {
      @Override
      public SymlinkPaths resolveSymlinkPaths(SourcePathResolverAdapter resolver) {
        return new SymlinkDirPaths(resolver.getAbsolutePath(getDirectory())) {
          @Override
          public void forEachSymlink(SymlinkConsumer consumer) throws IOException {
            super.forEachSymlink(
                (dst, src) -> {
                  if (accept(getType(), dst)) {
                    consumer.accept(dst, src);
                  }
                });
          }
        };
      }
    };
  }

  @Override
  public Resolved resolvePythonComponents(SourcePathResolverAdapter resolver) {
    Path directory = resolver.getAbsolutePath(getDirectory());
    Type type = getType();
    return (consumer) ->
        Files.walkFileTree(
            directory,
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                if (accept(type, file)) {
                  consumer.accept(directory.relativize(file), file);
                }
                return super.visitFile(file, attrs);
              }
            });
  }

  /** The type of components provided by a {@link PrebuiltPythonLibrary}. */
  protected enum Type {
    MODULES,
    RESOURCES,
    SOURCES,
  }
}
