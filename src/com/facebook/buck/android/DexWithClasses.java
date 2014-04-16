/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

import java.nio.file.Path;
import java.util.Comparator;

import javax.annotation.Nullable;

/**
 * Object that represents a {@code .dex.jar} file that knows what {@code .class} files went into it,
 * as well as its estimated linear alloc size.
 */
public interface DexWithClasses {

  /** @return path from the project root where the {@code .dex.jar} file can be found. */
  public Path getPathToDexFile();

  /** @return the names of the {@code .class} files that went into the DEX file. */
  public ImmutableSet<String> getClassNames();

  /** @return a hash of the {@code .class} files that went into the DEX file.*/
  public Sha1HashCode getClassesHash();

  /**
   * @return A value that estimates how much space the Dalvik code represented by this object will
   *     take up in a DEX file. The units for this estimate are not important, so long as they are
   *     consistent with those used by {@link PreDexedFilesSorter} to determine how secondary DEX
   *     files should be packed.
   */
  public int getSizeEstimate();

  public static Function<DexProducedFromJavaLibrary, DexWithClasses> TO_DEX_WITH_CLASSES =
      new Function<DexProducedFromJavaLibrary, DexWithClasses>() {
    @Override
    @Nullable
    public DexWithClasses apply(DexProducedFromJavaLibrary preDex) {
      if (!preDex.hasOutput()) {
        return null;
      }

      final Path pathToDex = preDex.getPathToDex();
      final ImmutableSet<String> classNames = preDex.getClassNames().keySet();
      final Sha1HashCode classesHash = Sha1HashCode.fromHashCode(
          Hashing.combineOrdered(preDex.getClassNames().values()));
      final int linearAllocEstimate = preDex.getLinearAllocEstimate();
      return new DexWithClasses() {
        @Override
        public Path getPathToDexFile() {
          return pathToDex;
        }

        @Override
        public ImmutableSet<String> getClassNames() {
          return classNames;
        }

        @Override
        public Sha1HashCode getClassesHash() {
          return classesHash;
        }

        @Override
        public int getSizeEstimate() {
          return linearAllocEstimate;
        }
      };
    }
  };

  public static Comparator<DexWithClasses> DEX_WITH_CLASSES_COMPARATOR =
      new Comparator<DexWithClasses>() {
        @Override
        public int compare(DexWithClasses o1, DexWithClasses o2) {
          return o1.getPathToDexFile().compareTo(o2.getPathToDexFile());
        }
      };

  public static Function <DexWithClasses, Path> TO_PATH =
      new Function<DexWithClasses, Path>() {
        @Override
        public Path apply(DexWithClasses input) {
          return input.getPathToDexFile();
        }
      };
}
