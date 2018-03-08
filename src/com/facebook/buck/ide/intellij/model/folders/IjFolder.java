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

package com.facebook.buck.ide.intellij.model.folders;

import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/** A path which contains a set of sources we wish to present to IntelliJ. */
public abstract class IjFolder implements Comparable<IjFolder> {

  private static final ImmutableSortedSet<Path> EMPTY_INPUTS = ImmutableSortedSet.of();

  private final Path path;
  private final ImmutableSortedSet<Path> inputs;
  private final boolean wantsPackagePrefix;

  IjFolder(Path path, boolean wantsPackagePrefix, ImmutableSortedSet<Path> inputs) {
    this.path = path;
    this.wantsPackagePrefix = wantsPackagePrefix;
    this.inputs = (inputs == null) ? EMPTY_INPUTS : inputs;
  }

  IjFolder(Path path, boolean wantsPackagePrefix) {
    this(path, wantsPackagePrefix, EMPTY_INPUTS);
  }

  IjFolder(Path path) {
    this(path, false);
  }

  /** @return name IntelliJ would use to refer to this type of folder. */
  public abstract String getIjName();

  /** @return path that this folder represents relative to the project root. */
  public Path getPath() {
    return path;
  }

  /** @return set of input files corresponding to this folder. */
  public ImmutableSortedSet<Path> getInputs() {
    return inputs;
  }

  /** @return a IJFolderFactory to create new instances of the folder class. */
  protected abstract IJFolderFactory getFactory();

  /**
   * Create a copy of this folder using the specified path.
   *
   * @return a copy of this object with the new path
   */
  public IjFolder createCopyWith(Path path) {
    return getFactory().create(path, getWantsPackagePrefix(), getInputs());
  }

  /**
   * @return true if it should be marked as a java-resource or java-test-resource folder, false
   *     otherwise.
   */
  public boolean isResourceFolder() {
    return false;
  }

  /**
   * Used to make IntelliJ ignore the package name-&gt;folder structure convention and assume the
   * given package prefix. An example of a scenario this makes possible to achieve is having
   * java/src/Foo.java declare the package "org.bar" (instead of having the path to the file be
   * java/org/bar/Foo.java). The main effect of this is the elimination of IntelliJ warnings about
   * incorrect package prefixes and having it use the correct package when creating new files.
   *
   * @return whether to generate package prefix for this folder.
   */
  public boolean getWantsPackagePrefix() {
    return wantsPackagePrefix;
  }

  public boolean canMergeWith(IjFolder other) {
    return other != null
        && getClass().equals(other.getClass())
        && getWantsPackagePrefix() == other.getWantsPackagePrefix()
        && getPath().startsWith(other.getPath());
  }

  public IjFolder merge(IjFolder otherFolder) {
    if (equals(otherFolder)) {
      return this;
    }

    return getFactory()
        .create(
            otherFolder.getPath(),
            getWantsPackagePrefix() || otherFolder.getWantsPackagePrefix(),
            combineInputs(this, otherFolder));
  }

  @Override
  public int compareTo(IjFolder other) {
    return path.compareTo(other.getPath());
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (other == null || !this.getClass().equals(other.getClass())) {
      return false;
    }

    IjFolder otherFolder = (IjFolder) other;
    return (hashCode() == otherFolder.hashCode())
        && getPath().equals(otherFolder.getPath())
        && (getWantsPackagePrefix() == otherFolder.getWantsPackagePrefix())
        && getInputs().equals(otherFolder.getInputs());
  }

  @Override
  public int hashCode() {
    return (getPath().hashCode() << 31)
        ^ (getWantsPackagePrefix() ? 0x8000 : 0)
        ^ inputs.hashCode();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + " for "
        + getPath()
        + (wantsPackagePrefix ? " wanting a package prefix" : "")
        + " covering "
        + getInputs();
  }

  public static ImmutableSortedSet<Path> combineInputs(IjFolder first, IjFolder second) {
    return ImmutableSortedSet.<Path>naturalOrder()
        .addAll(first.getInputs())
        .addAll(second.getInputs())
        .build();
  }
}
