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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

/**
 * Tool to get stats about dalvik classes.
 */
public class DalvikStatsTool {

  // Reasonable defaults based on dreiss's observations.
  private static final ImmutableMap<Pattern, Integer> PENALTIES =
      ImmutableMap.<Pattern, Integer>builder()
          .put(Pattern.compile("Layout$"), 1500)
          .put(Pattern.compile("View$"), 1500)
          .put(Pattern.compile("ViewGroup$"), 1800)
          .put(Pattern.compile("Activity$"), 1100)
          .build();

  // DX translates MULTIANEWARRAY into a method call that matches this (owner,name,desc)
  private static final String MULTIARRAY_OWNER = Type.getType(Array.class).getInternalName();
  private static final String MULTIARRAY_NAME = "newInstance";
  private static final String MULTIARRAY_DESC = Type.getMethodType(
      Type.getType(Object.class),
      Type.getType(Class.class),
      Type.getType("[" + Type.INT_TYPE.getDescriptor())).getDescriptor();

  public static class MethodReference {

    public final String className;
    public final String methodName;
    public final String methodDesc;


    public MethodReference(String className, String methodName, String methodDesc) {
      this.className = className;
      this.methodName = methodName;
      this.methodDesc = methodDesc;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      MethodReference that = (MethodReference) o;

      if (className != null ? !className.equals(that.className) : that.className != null)
        return false;
      if (methodDesc != null ? !methodDesc.equals(that.methodDesc) : that.methodDesc != null)
        return false;
      if (methodName != null ? !methodName.equals(that.methodName) : that.methodName != null)
        return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = className != null ? className.hashCode() : 0;
      result = 31 * result + (methodName != null ? methodName.hashCode() : 0);
      result = 31 * result + (methodDesc != null ? methodDesc.hashCode() : 0);
      return result;
    }

    @Override
    public String toString() {
      return className + "." + methodName + ":" + methodDesc;
    }
  }

  /**
   * Stats about a java class.
   */
  public static class Stats {

    public static final Stats ZERO = new Stats(0, ImmutableSet.<MethodReference>of());

    /** Estimated bytes the class will contribute to Dalvik linear alloc. */
    public final int estimatedLinearAllocSize;

    /** Methods referenced by the class. */
    public final ImmutableSet<MethodReference> methodReferences;

    public Stats(int estimatedLinearAllocSize, Set<MethodReference> methodReferences) {
      this.estimatedLinearAllocSize = estimatedLinearAllocSize;
      this.methodReferences = ImmutableSet.copyOf(methodReferences);
    }
  }

  /**
   * CLI wrapper to run against every class in a set of JARs.
   */
  public static void main(String[] args) throws IOException {
    for (String fname : args) {
      try (ZipFile inJar = new ZipFile(fname)) {
        for (ZipEntry entry : Collections.list(inJar.entries())) {
          if (!entry.getName().endsWith(".class")) {
            continue;
          }
          InputStream rawClass = inJar.getInputStream(entry);
          int footprint = getEstimate(rawClass).estimatedLinearAllocSize;
          System.out.println(footprint + "\t" + entry.getName().replace(".class", ""));
        }
      }
    }
  }

  /**
   * Estimates the footprint that a given class will have in the LinearAlloc buffer
   * of Android's Dalvik VM.
   *
   * @param rawClass Raw bytes of the Java class to analyze.
   * @return the estimate
   */
  public static Stats getEstimate(InputStream rawClass) throws IOException {
    ClassReader classReader = new ClassReader(rawClass);
    return getEstimateInternal(classReader);
  }

  /**
   * Estimates the footprint that a given class will have in the LinearAlloc buffer
   * of Android's Dalvik VM.
   *
   * @param classReader reader containing the Java class to analyze.
   * @return the estimate
   */
  @VisibleForTesting
  static Stats getEstimateInternal(ClassReader classReader) throws IOException {
    // SKIP_FRAMES was required to avoid an exception in ClassReader when running on proguard
    // output. We don't need to visit frames so this isn't an issue.
    StatsClassVisitor statsVisitor = new StatsClassVisitor(PENALTIES);
    classReader.accept(statsVisitor, ClassReader.SKIP_FRAMES);
    return new Stats(
        statsVisitor.footprint,
        statsVisitor.methodReferenceBuilder.build());
  }

  private static class StatsClassVisitor extends ClassVisitor {

    private final ImmutableMap<Pattern, Integer> penalties;
    private final MethodVisitor methodVisitor = new StatsMethodVisitor();
    private int footprint;
    private boolean isInterface;
    private ImmutableSet.Builder<MethodReference> methodReferenceBuilder;

    private String className;

    private StatsClassVisitor(Map<Pattern, Integer> penalties) {
      super(Opcodes.ASM4);
      this.penalties = ImmutableMap.copyOf(penalties);
      this.methodReferenceBuilder = ImmutableSet.builder();
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {

      this.className = name;
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
      methodReferenceBuilder.add(new MethodReference(className, name, desc));
      return methodVisitor;
    }

    @Override
    public void visitOuterClass(String owner, String name, String desc) {
      super.visitOuterClass(owner, name, desc);
      if (name != null) {
        methodReferenceBuilder.add(new MethodReference(className, name, desc));
      }
    }

    private class StatsMethodVisitor extends MethodVisitor {

      public StatsMethodVisitor() {
        super(Opcodes.ASM4);
      }

      @Override
      public void visitMethodInsn(int opcode, String owner, String name, String desc) {
        super.visitMethodInsn(opcode, owner, name, desc);
        methodReferenceBuilder.add(new MethodReference(owner, name, desc));
      }

      @Override
      public void visitMultiANewArrayInsn(String desc, int dims) {
        // dx translates this instruction into a method invocation on
        // Array.newInstance(Class clazz, int...dims);
        methodReferenceBuilder.add(
            new MethodReference(MULTIARRAY_OWNER, MULTIARRAY_NAME, MULTIARRAY_DESC));
      }
    }
  }
}
