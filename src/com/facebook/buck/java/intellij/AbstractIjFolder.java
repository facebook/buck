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

package com.facebook.buck.java.intellij;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;

import org.immutables.value.Value;

import java.nio.file.Path;

/**
 * A path which contains a set of sources we wish to present to IntelliJ.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjFolder {
  public enum Type {
    EXCLUDE_FOLDER("excludeFolder"),
    GENERATED_FOLDER("generatedFolder"),
    SOURCE_FOLDER("sourceFolder"),
    TEST_FOLDER("testFolder")
    ;

    private final String value;

    Type(String value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  public abstract Type getType();
  protected abstract Optional<Path> getModuleRelativePath();
  protected abstract Optional<SourcePath> getSourcePath();

  /**
   * @return whether the sources in this folder should inherit the package prefix related to
   * the module in which they reside.
   */
  public abstract boolean getWantsPackagePrefix();

  @Value.Derived
  public boolean isTest() {
    return getType() == Type.TEST_FOLDER;
  }

  @Value.Check
  public void eitherModuleRelativePathOrSourcePathArePresent() {
    Preconditions.checkArgument(getModuleRelativePath().isPresent() ^ getSourcePath().isPresent());
  }

  /**
   * Depending on how the {@link IjFolder} was created it could hold a {@link Path}
   * or {@link SourcePath}. This method sort of encapsulates that.
   *
   * @param resolver resolver for the project
   * @return path, relative to the module base path that represents this Folder.
   */
  public Path resolveModuleRelativePath(final SourcePathResolver resolver) {
    return getModuleRelativePath().or(
        new Supplier<Path>() {
          @Override
          public Path get() {
            return resolver.getRelativePath(getSourcePath().get()).get();
          }
        });
  }
}
