/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.tools.dxanalysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

public class MutabilityAnalyzer {
  /**
   *  Deeply immutable "sorts" of types.  See {@link org.objectweb.asm.Type#getSort()}.
   */
  static final ImmutableSet<Integer> IMMUTABLE_TYPE_SORTS = ImmutableSet.of(
      Type.BOOLEAN,
      Type.BYTE,
      Type.CHAR,
      Type.DOUBLE,
      Type.FLOAT,
      Type.INT,
      Type.LONG,
      Type.SHORT);

  /**
   * External classes that are final and deeply immutable.
   */
  private static final ImmutableSet<String> EXTERNAL_IMMUTABLE_CLASSES = ImmutableSet.of(
      "java/lang/String",
      "java/lang/Integer"
  );

  /**
   * NonExternal classes that are non-final but have no inherent mutability.
   */
  public static final ImmutableSet<String> EXTERNAL_IMMUTABLE_BASE_CLASSES = ImmutableSet.of(
      "java/lang/Object",
      "java/lang/Enum"
  );

  /**
   * All classes under analysis.
   */
  private final ImmutableMap<String, ClassNode> allClasses;

  /**
   * Any class that is definitely mutable.  All subclasses become mutable as well.
   * Superclasses are marked has having mutable descendents.
   */
  private final Set<String> trulyMutableClasses;

  /**
   * Classes that have truly mutable descendents.  Having a field of one of these types
   * makes you truly mutable, but extending one does not.
   */
  private final Set<String> classesWithMutableDescendents;

  /**
   * Only set once in go().
   */
  @Nullable
  private ImmutableSet<String> immutableClasses;

  /**
   * True iff we made progress during this round.
   */
  private boolean madeProgress;

  /**
   * Log of messages.
   */
  private List<String> log = new ArrayList<>();

  public static MutabilityAnalyzer analyze(
      ImmutableMap<String, ClassNode> allClasses) {
    MutabilityAnalyzer analyzer = new MutabilityAnalyzer(allClasses);
    analyzer.go();
    return analyzer;
  }

  private MutabilityAnalyzer(ImmutableMap<String, ClassNode> allClasses) {
    this.allClasses = allClasses;
    trulyMutableClasses = new HashSet<>();
    classesWithMutableDescendents = new HashSet<>();
  }

  public ImmutableList<String> getLog() {
    return ImmutableList.copyOf(log);
  }

  public ImmutableSet<String> getImmutableClasses() {
    return immutableClasses;
  }

  public boolean isTypeImmutable(String desc) {
    Type type = Type.getType(desc);
    if (IMMUTABLE_TYPE_SORTS.contains(type.getSort())) {
      return true;
    }
    if (type.getSort() != Type.OBJECT) {
      return false;
    }
    if (Sets.union(EXTERNAL_IMMUTABLE_CLASSES, immutableClasses)
        .contains(type.getInternalName())) {
      return true;
    }
    return false;
  }

  private void go() {
    while (true) {
      madeProgress = false;
      for (ClassNode klass : allClasses.values()) {
        analyzeClass(klass);
      }
      if (!madeProgress) {
        break;
      }
    }

    immutableClasses = ImmutableSet.copyOf(
        Sets.difference(
            allClasses.keySet(),
            Sets.union(trulyMutableClasses, classesWithMutableDescendents)));
  }

  private void markClassTrulyMutable(String className) {
    if (trulyMutableClasses.contains(className)) {
      return;
    }
    if (!allClasses.containsKey(className)) {
      throw new RuntimeException("Tried to mark external class '" + className + "' truly mutable");
    }

    trulyMutableClasses.add(className);
    madeProgress = true;

    markClassAsHavingMutableDescendents(className);
  }

  private void markClassAsHavingMutableDescendents(String className) {
    if (classesWithMutableDescendents.contains(className)) {
      return;
    }
    ClassNode klass = allClasses.get(className);
    if (klass == null) {
      return;
    }

    classesWithMutableDescendents.add(className);
    madeProgress = true;

    markClassAsHavingMutableDescendents(klass.superName);
    for (String iface : klass.interfaces) {
      markClassAsHavingMutableDescendents(iface);
    }
  }

  private boolean superClassIsDefinitelyMutable(String superClassName) {
    if (trulyMutableClasses.contains(superClassName)) {
      return true;
    }
    if (EXTERNAL_IMMUTABLE_BASE_CLASSES.contains(superClassName)) {
      return false;
    }
    if (!allClasses.containsKey(superClassName)) {
      // All external classes are assumed to be mutable unless whitelisted.
      return true;
    }
    // Assume internal classes are immutable until proven otherwise (in later rounds).
    return false;
  }

  private boolean classIsDefinitelyMutable(ClassNode klass) {
    if (superClassIsDefinitelyMutable(klass.superName)) {
      log.add("Mutable parent: " + klass.name + " < " + klass.superName);
      return true;
    }

    for (FieldNode field : klass.fields) {
      if ((field.access & Opcodes.ACC_STATIC) != 0) {
        continue;
      }
      if ((field.access & Opcodes.ACC_FINAL) == 0) {
        log.add("Non-final field: " + klass.name + "#" + field.name + ":" + field.desc);
        return true;
      }
      if (field.name.contains("$")) {
        // Generated fields are assumed to be effectively immutable.
        // This could, in principle, miss an issue like a static reference to a
        // seemingly-immutable inner class object that maintains a hidden reference
        // to its mutable outer object, but that seems unlikely.
        continue;
      }

      Type type = Type.getType(field.desc);

      if (IMMUTABLE_TYPE_SORTS.contains(type.getSort())) {
        continue;
      }

      if (type.getSort() != Type.OBJECT) {
        log.add("Odd sort: " + klass.name + "#" + field.name + ":" + field.desc);
        return true;
      }

      if (allClasses.keySet().contains(type.getInternalName())) {
        if (classesWithMutableDescendents.contains(type.getInternalName())) {
          log.add("Internal mutable field: " + klass.name + "#" + field.name + ":" + field.desc);
          return true;
        }
      } else {
        if (!EXTERNAL_IMMUTABLE_CLASSES.contains(type.getInternalName())) {
          log.add("External mutable field: " + klass.name + "#" + field.name + ":" + field.desc);
          return true;
        }
      }
    }

    return false;
  }

  private void analyzeClass(ClassNode klass) {
    if (trulyMutableClasses.contains(klass.name)) {
      return;
    }
    if (classIsDefinitelyMutable(klass)) {
      markClassTrulyMutable(klass.name);
    }
  }

}
