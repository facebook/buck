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

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This is a command-line tool for performing ad-hoc static analysis of the DX tool.
 * Specifically, it is intended to find structures that would make it unsafe to run
 * within the buckd process.
 */
public class DxAnalysisMain {
  private static final boolean DEBUG_MUTABILITY = false;

  private DxAnalysisMain() {}

  /**
   * @param args  One argument is expected: the path to dx.jar.
   */
  public static void main(String[] args) throws IOException {
    String zipFileName = args[0];
    ImmutableMap<String, ClassNode> allClasses = loadAllClasses(zipFileName);

    MutabilityAnalyzer mutability = MutabilityAnalyzer.analyze(allClasses);
    StaticStateAnalyzer staticState = StaticStateAnalyzer.analyze(allClasses, mutability);
    RegisterSpecAnalyzer registerSpec = RegisterSpecAnalyzer.analyze(allClasses);

    if (DEBUG_MUTABILITY) {
      for (String l : mutability.getLog()) {
        System.out.println(l);
      }
      System.out.println();
      System.out.println();
      System.out.println();

      for (String ic : mutability.getImmutableClasses()) {
        System.out.println("Immutable: " + ic);
      }
      System.out.println();
      System.out.println();
      System.out.println();
    }

    for (String l : staticState.getLog()) {
      System.out.println(l);
    }
    System.out.println();
    System.out.println();
    System.out.println();

    for (String l : registerSpec.getLog()) {
      System.out.println(l);
    }
    System.out.println();
    System.out.println();
    System.out.println();

  }

  private static ImmutableMap<String, ClassNode> loadAllClasses(String zipFileName)
      throws IOException {
    ImmutableMap.Builder<String, ClassNode> allClassesBuilder = ImmutableMap.builder();
    try (ZipFile inJar = new ZipFile(zipFileName)) {
      for (ZipEntry entry : Collections.list(inJar.entries())) {
        if (!entry.getName().endsWith(".class")) {
          continue;
        }
        // Skip external libraries.
        if (
            entry.getName().startsWith("junit/") ||
            entry.getName().startsWith("org/junit/") ||
            entry.getName().startsWith("com/google/common/")) {
          continue;
        }
        byte[] rawClass = ByteStreams.toByteArray(inJar.getInputStream(entry));
        ClassNode klass = new ClassNode();
        new ClassReader(rawClass).accept(klass, ClassReader.EXPAND_FRAMES);
        allClassesBuilder.put(klass.name, klass);
      }
    }
    return allClassesBuilder.build();
  }
}
