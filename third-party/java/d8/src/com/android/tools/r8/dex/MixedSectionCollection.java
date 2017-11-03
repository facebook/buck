// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationDirectory;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexAnnotationSetRefList;
import com.android.tools.r8.graph.DexCode;
import com.android.tools.r8.graph.DexDebugInfo;
import com.android.tools.r8.graph.DexEncodedArray;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexTypeList;

/**
 * Collection of the various components of the mixed section of a dex file.
 *
 * <p>This semantically is just a wrapper around a bunch of collections. We do not expose the
 * collections directly to allow for implementations that under the hood do not use collections.
 *
 * <p>See {@link DexItem#collectMixedSectionItems(MixedSectionCollection)} for
 * information on how to fill a {@link MixedSectionCollection}.
 */
public abstract class MixedSectionCollection {

  /**
   * Adds the given class data to the collection.
   *
   * Does not add any dependencies.
   *
   * @return true if the item was not added before
   */
  public abstract boolean add(DexProgramClass dexClassData);

  /**
   * Adds the given encoded array to the collection.
   *
   * Does not add any dependencies.
   *
   * @return true if the item was not added before
   */
  public abstract boolean add(DexEncodedArray dexEncodedArray);

  /**
   * Adds the given annotation set to the collection.
   *
   * Does not add any dependencies.
   *
   * @return true if the item was not added before
   */
  public abstract boolean add(DexAnnotationSet dexAnnotationSet);

  /**
   * Adds the given code item to the collection.
   *
   * Does not add any dependencies.
   *
   * @return true if the item was not added before
   */
  public abstract boolean add(DexCode dexCode);

  /**
   * Adds the given debug info to the collection.
   *
   * Does not add any dependencies.
   *
   * @return true if the item was not added before
   */
  public abstract boolean add(DexDebugInfo dexDebugInfo);

  /**
   * Adds the given type list to the collection.
   *
   * Does not add any dependencies.
   *
   * @return true if the item was not added before
   */
  public abstract boolean add(DexTypeList dexTypeList);

  /**
   * Adds the given annotation-set reference list to the collection.
   *
   * Does not add any dependencies.
   *
   * @return true if the item was not added before
   */
  public abstract boolean add(DexAnnotationSetRefList annotationSetRefList);

  /**
   * Adds the given annotation to the collection.
   *
   * Does not add any dependencies.
   *
   * @return true if the item was not added before
   */
  public abstract boolean add(DexAnnotation annotation);

  /**
   * Adds the given annotation directory to the collection.
   *
   * Add a dependency between the clazz and the annotation directory.
   *
   * @return true if the item was not added before
   */
  public abstract boolean setAnnotationsDirectoryForClass(DexProgramClass clazz,
      DexAnnotationDirectory annotationDirectory);
}
