// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.ClasspathClassCollection;
import com.android.tools.r8.utils.LibraryClassCollection;
import com.android.tools.r8.utils.ProgramClassCollection;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableSet;
import java.util.IdentityHashMap;
import java.util.Map;

public class LazyLoadedDexApplication extends DexApplication {

  private ClasspathClassCollection classpathClasses;
  private LibraryClassCollection libraryClasses;

  /**
   * Constructor should only be invoked by the DexApplication.Builder.
   */
  private LazyLoadedDexApplication(ClassNameMapper proguardMap,
      ProgramClassCollection programClasses,
      ClasspathClassCollection classpathClasses,
      LibraryClassCollection libraryClasses,
      ImmutableSet<DexType> mainDexList, byte[] deadCode,
      DexItemFactory dexItemFactory, DexString highestSortingString,
      Timing timing) {
    super(proguardMap, programClasses, mainDexList, deadCode,
        dexItemFactory, highestSortingString, timing);
    this.classpathClasses = classpathClasses;
    this.libraryClasses = libraryClasses;
  }

  @Override
  public DexClass definitionFor(DexType type) {
    if (type == null) {
      return null;
    }
    DexClass clazz = programClasses.get(type);
    if (clazz == null && classpathClasses != null) {
      clazz = classpathClasses.get(type);
    }
    if (clazz == null && libraryClasses != null) {
      clazz = libraryClasses.get(type);
    }
    return clazz;
  }

  private Map<DexType, DexClass> forceLoadAllClasses() {
    Map<DexType, DexClass> loaded = new IdentityHashMap<>();

    // Program classes are supposed to be loaded, but force-loading them is no-op.
    programClasses.forceLoad(type -> true);
    programClasses.getAllClasses().forEach(clazz -> loaded.put(clazz.type, clazz));

    if (classpathClasses != null) {
      classpathClasses.forceLoad(type -> !loaded.containsKey(type));
      classpathClasses.getAllClasses().forEach(clazz -> loaded.putIfAbsent(clazz.type, clazz));
    }

    if (libraryClasses != null) {
      libraryClasses.forceLoad(type -> !loaded.containsKey(type));
      libraryClasses.getAllClasses().forEach(clazz -> loaded.putIfAbsent(clazz.type, clazz));
    }

    return loaded;
  }

  /**
   * Force load all classes and return type -> class map containing all the classes.
   */
  public Map<DexType, DexClass> getFullClassMap() {
    return forceLoadAllClasses();
  }

  public static class Builder extends DexApplication.Builder<Builder> {

    private ClasspathClassCollection classpathClasses;
    private LibraryClassCollection libraryClasses;

    Builder(DexItemFactory dexItemFactory, Timing timing) {
      super(dexItemFactory, timing);
      this.classpathClasses = null;
      this.libraryClasses = null;
    }

    private Builder(LazyLoadedDexApplication application) {
      super(application);
      this.classpathClasses = application.classpathClasses;
      this.libraryClasses = application.libraryClasses;
    }

    @Override
    Builder self() {
      return this;
    }

    public Builder setClasspathClassCollection(ClasspathClassCollection classes) {
      this.classpathClasses = classes;
      return this;
    }

    public Builder setLibraryClassCollection(LibraryClassCollection classes) {
      this.libraryClasses = classes;
      return this;
    }

    @Override
    public LazyLoadedDexApplication build() {
      return new LazyLoadedDexApplication(proguardMap,
          ProgramClassCollection.create(programClasses),
          classpathClasses, libraryClasses, ImmutableSet.copyOf(mainDexList), deadCode,
          dexItemFactory, highestSortingString, timing);
    }
  }

  @Override
  public Builder builder() {
    return new Builder(this);
  }

  @Override
  public DirectMappedDexApplication toDirect() {
    return new DirectMappedDexApplication.Builder(this).build().asDirect();
  }

  @Override
  public String toString() {
    return "Application (" + programClasses + "; " + classpathClasses + "; " + libraryClasses
        + ")";
  }
}
