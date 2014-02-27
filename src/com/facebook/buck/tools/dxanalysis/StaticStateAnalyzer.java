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

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

public class StaticStateAnalyzer {
  private final MutabilityAnalyzer mutabilityAnalyzer;
  private final ImmutableMap<String, ClassNode> allClasses;

  /**
   * Only set once in go().
   */
  @Nullable
  private ImmutableSet<String> unsafeClasses;


  /**
   * Log of messages.
   */
  private List<String> log = new ArrayList<>();


  public static StaticStateAnalyzer analyze(
      ImmutableMap<String, ClassNode> allClasses,
      MutabilityAnalyzer mutabilityAnalyzer) {
    StaticStateAnalyzer analyzer = new StaticStateAnalyzer(allClasses, mutabilityAnalyzer);
    analyzer.go();
    return analyzer;
  }

  private StaticStateAnalyzer(
      ImmutableMap<String, ClassNode> allClasses,
      MutabilityAnalyzer mutabilityAnalyzer) {
    this.allClasses = allClasses;
    this.mutabilityAnalyzer = mutabilityAnalyzer;
  }

  public ImmutableList<String> getLog() {
    return ImmutableList.copyOf(log);
  }

  public ImmutableSet<String> getUnsafeClasses() {
    return unsafeClasses;
  }

  private void go() {
    ImmutableSet.Builder<String> unsafeClassesBuilder = ImmutableSet.builder();

    for (ClassNode klass : allClasses.values()) {
      boolean classIsSafe = isClassSafe(klass);
      if (!classIsSafe) {
        unsafeClassesBuilder.add(klass.name);
      }
    }

    unsafeClasses = unsafeClassesBuilder.build();
  }

  private boolean isClassSafe(ClassNode klass) {
    boolean isSafe = true;

    // Look for mutable static fields.
    for (FieldNode field : klass.fields) {
      if ((field.access & Opcodes.ACC_STATIC) == 0) {
        continue;
      }
      if ((field.access & Opcodes.ACC_FINAL) == 0) {
        log.add("Non-final static field: " + describe(klass, field));
        isSafe = false;
        continue;
      }
      if (!mutabilityAnalyzer.isTypeImmutable(field.desc)) {
        log.add("Mut-final static field: " + describe(klass, field));
        isSafe = false;
        continue;
      }
    }

    // Look for static synchronized methods.
    for (MethodNode method : klass.methods) {
      if ((method.access & Opcodes.ACC_STATIC) == 0) {
        continue;
      }

      if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
        log.add("Synchronized static method: " + describe(klass, method));
        isSafe = false;
        continue;
      }
    }

    return isSafe;
  }

  private static String describe(ClassNode klass, FieldNode field) {
    return describe(klass, field.name);
  }

  private static String describe(ClassNode klass, MethodNode method) {
    return describe(klass, method.name);
  }

  private static String describe(ClassNode klass, String member) {
    return klass.name + "#" + member;
  }
}
