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

  /**
   * @return path that this folder represents relative to the project root.
   */
  public abstract Path getPath();

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


  @Value.Derived
  public boolean isTest() {
    return getType() == Type.TEST_FOLDER;
  }

  @Value.Check
  protected void packagePrefixOnlyOnSources() {
    Preconditions.checkArgument(!getWantsPackagePrefix() ||
            Type.SOURCE_FOLDER.equals(getType()) ||
            Type.TEST_FOLDER.equals(getType()));
  }
}
