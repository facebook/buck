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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;

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
    SOURCE_FOLDER("sourceFolder"),
    TEST_FOLDER("sourceFolder"); // The test folder is a sourceFolder with a separate 'isTest' flag.

    private final String ijName;

    Type(String ijName) {
      this.ijName = ijName;
    }

    /**
     * @return name IntelliJ would use to refer to this type of folder.
     */
    public String getIjName() {
      return ijName;
    }

    /**
     * It's possible, by the use of glob patterns, to have different types of targets include
     * sources from the same same folder. Since IntelliJ operates at the level of individual
     * folders, not files this can result in the same {@link IjFolder} being marked with different
     * types. This method implements the logic to merge the types of these folders.
     *
     * @param left type to merge, order does not matter.
     * @param right type to merge, order does not matter.
     * @return merged type.
     */
    public static Type merge(Type left, Type right) {
      Preconditions.checkNotNull(left);
      Preconditions.checkNotNull(right);
      if (left.equals(right)) {
        return left;
      }
      Preconditions.checkArgument(!left.equals(EXCLUDE_FOLDER) && !right.equals(EXCLUDE_FOLDER),
          "Exclude folders cannot merge with other types.");

      // Since left and right are not equal, and we've excluded EXCLUDE they have to be a mix of
      // SOURCE and TEST. A mix of SOURCE and TEST gets promoted to SOURCE.
      return SOURCE_FOLDER;
    }
  }

  public abstract Type getType();

  /**
   * @return path that this folder represents relative to the project root.
   */
  public abstract Path getPath();

  /**
   * @return set of input files corresponding to this folder.
   */
  public abstract ImmutableSortedSet<Path> getInputs();

  /**
   * Used to make IntelliJ ignore the package name->folder structure convention and assume the
   * given package prefix. An example of a scenario this makes possible to achieve is having
   * java/src/Foo.java declare the package "org.bar" (instead of having the path to the file be
   * java/org/bar/Foo.java).
   * The main effect of this is the elimination of IntelliJ warnings about incorrect package
   * prefixes and having it use the correct package when creating new files.
   *
   * @return whether to generate package prefix for this folder.
   */
  public abstract boolean getWantsPackagePrefix();

  public boolean isTest() {
    return getType() == Type.TEST_FOLDER;
  }

  public IjFolder merge(IjFolder otherFolder) {
    Preconditions.checkArgument(otherFolder.getPath().equals(getPath()));

    return otherFolder
        .withWantsPackagePrefix(getWantsPackagePrefix() || otherFolder.getWantsPackagePrefix())
        .withType(Type.merge(getType(), otherFolder.getType()))
        .withInputs(ImmutableSortedSet.<Path>naturalOrder()
                .addAll(getInputs())
                .addAll(otherFolder.getInputs())
                .build());
  }

  @Value.Check
  protected void packagePrefixOnlyOnSources() {
    Preconditions.checkArgument(!getWantsPackagePrefix() ||
            Type.SOURCE_FOLDER.equals(getType()) ||
            Type.TEST_FOLDER.equals(getType()));
  }
}
