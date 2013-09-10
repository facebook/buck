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

package com.facebook.buck.dalvik;

import com.google.common.collect.ImmutableMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

public class LinearAllocEstimator {

  // TODO(user): This class needs a unit test.

  // Reasonable defaults based on dreiss's observations.
  private static final ImmutableMap<Pattern, Integer> PENALTIES = ImmutableMap.of(
      Pattern.compile("Layout$"), 1500,
      Pattern.compile("View$"), 1500,
      Pattern.compile("ViewGroup$"), 1800,
      Pattern.compile("Activity$"), 1100
  );

  /**
   * CLI wrapper to run against every class in a set of JARs.
   */
  public static void main(String[] args) throws IOException {
    for (String fname : args) {
      ZipFile inJar = new ZipFile(fname);
      for (ZipEntry entry : Collections.list(inJar.entries())) {
        if (!entry.getName().endsWith(".class")) {
          continue;
        }
        InputStream rawClass = inJar.getInputStream(entry);
        int footprint = estimateLinearAllocFootprint(rawClass, PENALTIES);
        System.out.println(footprint + "\t" + entry.getName().replace(".class", ""));
      }
    }
  }

  public static int estimateLinearAllocFootprint(InputStream rawClass) throws IOException {
    return estimateLinearAllocFootprint(rawClass, PENALTIES);
  }

  /**
   * Estimates the footprint that a given class will have in the LinearAlloc buffer
   * of Android's Dalvik VM.
   *
   *
   * @param rawClass Raw bytes of the Java class to analyze.
   * @param penalties Map from regex patterns to run against the internal name of the class and
   *                  its parent to a "penalty" to apply to the footprint, representing the size
   *                  of the vtable of the parent class.
   * @return Estimated footprint in bytes.
   */
  public static int estimateLinearAllocFootprint(
      InputStream rawClass,
      ImmutableMap<Pattern, Integer> penalties) throws IOException {
    EstimatorVisitor estimatorVisitor = new EstimatorVisitor(penalties);
    new ClassReader(rawClass).accept(estimatorVisitor, 0);
    return estimatorVisitor.getFootprint();
  }

  private static class EstimatorVisitor extends ClassVisitor {
    private final ImmutableMap<Pattern, Integer> penalties;
    private int footprint;
    private boolean isInterface;

    private EstimatorVisitor(Map<Pattern, Integer> penalties) {
      super(Opcodes.ASM4);
      this.penalties = ImmutableMap.copyOf(penalties);
    }

    public int getFootprint() {
      return footprint;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {

      if ((access & (Opcodes.ACC_INTERFACE)) != 0) {
        // Interfaces don't have vtables.
        // This might undercount annotations, but they are mostly small.
        isInterface = true;
      } else {
        // Some parent classes have big vtable footprints.  We try to estimate the parent vtable
        // size based on the name of the class and parent class.  This seems to work reasonably
        // well in practice because the heaviest vtables are View and Activity, and many of those
        // classes have clear names and cannot be obfuscated.
        // Non-interfaces inherit the java.lang.Object vtable, which is 48 bytes.
        int vtablePenalty = 48;

        String[] names = new String[]{name, superName};
        for (Map.Entry<Pattern, Integer> entry : penalties.entrySet()) {
          for (String cls : names) {
            if (entry.getKey().matcher(cls).find()) {
              vtablePenalty = Math.max(vtablePenalty, entry.getValue());
            }
          }
        }
        footprint += vtablePenalty;
      }

    }

    @Override
    @Nullable
    public FieldVisitor visitField(
        int access, String name, String desc, String signature, Object value) {
      // For non-static fields, Field objects are 16 bytes.
      if ((access & Opcodes.ACC_STATIC) == 0) {
        footprint += 16;
      }

      return null;
    }

    @Override
    @Nullable
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      // Method objects are 52 bytes.
      footprint += 52;

      // For non-interfaces, each virtual method adds another 4 bytes to the vtable.
      if (!isInterface) {
        boolean isDirect =
            ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) ||
                name.equals("<init>");
        if (!isDirect) {
          footprint += 4;
        }
      }

      return null;
    }
  }
}
