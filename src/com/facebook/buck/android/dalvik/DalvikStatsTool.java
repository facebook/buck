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

package com.facebook.buck.android.dalvik;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

/** Tool to get stats about dalvik classes. */
public class DalvikStatsTool {

  /** Utility class: do not instantiate */
  private DalvikStatsTool() {}

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
  private static final String MULTIARRAY_DESC =
      Type.getMethodType(
              Type.getType(Object.class),
              Type.getType(Class.class),
              Type.getType("[" + Type.INT_TYPE.getDescriptor()))
          .getDescriptor();
  // The MULTINEWARRAY call has (e.g.) Integer.TYPE as an argument.
  private static final String PRIMITIVE_TYPE_FIELD_NAME = "TYPE";
  private static final String MULTIARRAY_ARG_TYPE = Type.getType(Class.class).getDescriptor();

  /** Stats about a java class. */
  public static class Stats {

    public static final Stats ZERO = new Stats(0, ImmutableSet.of(), ImmutableSet.of());

    /** Estimated bytes the class will contribute to Dalvik linear alloc. */
    public final int estimatedLinearAllocSize;

    /** Methods referenced by the class. */
    public final ImmutableSet<DalvikMemberReference> methodReferences;

    /** Fields referenced by the class. */
    public final ImmutableSet<DalvikMemberReference> fieldReferences;

    public Stats(
        int estimatedLinearAllocSize,
        Set<DalvikMemberReference> methodReferences,
        Set<DalvikMemberReference> fieldReferences) {
      this.estimatedLinearAllocSize = estimatedLinearAllocSize;
      this.methodReferences = ImmutableSet.copyOf(methodReferences);
      this.fieldReferences = ImmutableSet.copyOf(fieldReferences);
    }
  }

  /** CLI wrapper to run against every class in a set of JARs. */
  public static void main(String[] args) throws IOException {
    ImmutableSet.Builder<DalvikMemberReference> allFields = ImmutableSet.builder();

    for (String fname : args) {
      try (ZipFile inJar = new ZipFile(fname)) {
        for (ZipEntry entry : Collections.list(inJar.entries())) {
          if (!entry.getName().endsWith(".class")) {
            continue;
          }
          InputStream rawClass = inJar.getInputStream(entry);
          Stats stats = getEstimate(rawClass);
          System.out.println(
              stats.estimatedLinearAllocSize + "\t" + entry.getName().replace(".class", ""));
          allFields.addAll(stats.fieldReferences);
        }
      }
    }

    // Uncomment to debug fields.
    //    System.out.println();
    //
    //    for (DalvikMemberReference field : allFields.build()) {
    //      System.out.println(field);
    //    }
  }

  /**
   * Estimates the footprint that a given class will have in the LinearAlloc buffer of Android's
   * Dalvik VM.
   *
   * @param rawClass Raw bytes of the Java class to analyze.
   * @return the estimate
   */
  public static Stats getEstimate(InputStream rawClass) throws IOException {
    ClassReader classReader = new ClassReader(rawClass);
    return getEstimateInternal(classReader);
  }

  /**
   * Estimates the footprint that a given class will have in the LinearAlloc buffer of Android's
   * Dalvik VM.
   *
   * @param classReader reader containing the Java class to analyze.
   * @return the estimate
   */
  @VisibleForTesting
  static Stats getEstimateInternal(ClassReader classReader) {
    // SKIP_FRAMES was required to avoid an exception in ClassReader when running on proguard
    // output. We don't need to visit frames so this isn't an issue.
    StatsClassVisitor statsVisitor = new StatsClassVisitor(PENALTIES);
    classReader.accept(statsVisitor, ClassReader.SKIP_FRAMES);
    return new Stats(
        statsVisitor.footprint,
        statsVisitor.methodReferenceBuilder.build(),
        statsVisitor.fieldReferenceBuilder.build());
  }

  @Nullable
  private static String getBoxingType(final Type type) {
    switch (type.getSort()) {
      case Type.BOOLEAN:
        return "java/lang/Boolean";
      case Type.BYTE:
        return "java/lang/Byte";
      case Type.SHORT:
        return "java/lang/Short";
      case Type.CHAR:
        return "java/lang/Character";
      case Type.INT:
        return "java/lang/Integer";
      case Type.LONG:
        return "java/lang/Long";
      case Type.FLOAT:
        return "java/lang/Float";
      case Type.DOUBLE:
        return "java/lang/Double";
    }
    return null;
  }

  private static class StatsClassVisitor extends ClassVisitor {

    private final ImmutableMap<Pattern, Integer> penalties;
    private final MethodVisitor methodVisitor = new StatsMethodVisitor();
    private final FieldVisitor fieldVisitor = new StatsFieldVisitor();
    private final AnnotationVisitor annotationVisitor = new StatsAnnotationVisitor();
    private int footprint;
    private boolean isInterface;
    private ImmutableSet.Builder<DalvikMemberReference> methodReferenceBuilder;
    private ImmutableSet.Builder<DalvikMemberReference> fieldReferenceBuilder;

    @Nullable private String className;

    private StatsClassVisitor(Map<Pattern, Integer> penalties) {
      super(Opcodes.ASM5);
      this.penalties = ImmutableMap.copyOf(penalties);
      this.methodReferenceBuilder = ImmutableSet.builder();
      this.fieldReferenceBuilder = ImmutableSet.builder();
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

        String[] names = new String[] {name, superName};
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
    public FieldVisitor visitField(
        int access, String name, String desc, String signature, Object value) {
      // For non-static fields, Field objects are 16 bytes.
      if ((access & Opcodes.ACC_STATIC) == 0) {
        footprint += 16;
      }

      Preconditions.checkNotNull(className, "Must not call visitField before visit");
      fieldReferenceBuilder.add(DalvikMemberReference.of(className, name, desc));

      return fieldVisitor;
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
            ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) || name.equals("<init>");
        if (!isDirect) {
          footprint += 4;
        }
      }
      Preconditions.checkNotNull(className, "Must not call visitMethod before visit");
      methodReferenceBuilder.add(DalvikMemberReference.of(className, name, desc));
      return methodVisitor;
    }

    @Override
    public void visitOuterClass(String owner, String name, String desc) {
      super.visitOuterClass(owner, name, desc);
      if (name != null) {
        Preconditions.checkNotNull(className, "Must not call visitOuterClass before visit");
        methodReferenceBuilder.add(DalvikMemberReference.of(className, name, desc));
      }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return annotationVisitor;
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(
        int typeRef, TypePath typePath, String desc, boolean visible) {
      return annotationVisitor;
    }

    private class StatsMethodVisitor extends MethodVisitor {
      public StatsMethodVisitor() {
        super(Opcodes.ASM5);
      }

      @Override
      public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        super.visitFieldInsn(opcode, owner, name, desc);
        fieldReferenceBuilder.add(DalvikMemberReference.of(owner, name, desc));
      }

      @Override
      public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        super.visitMethodInsn(opcode, owner, name, desc, itf);
        methodReferenceBuilder.add(DalvikMemberReference.of(owner, name, desc));
      }

      @Override
      /**
       * Lambdas/Method Refs for D8 desugaring
       *
       * <p>Stateless (no arguments) - Adds 1 class, 1 field (for instance var) and 2 methods (1
       * constructor + 1 accessor) Stateful (has arguments) - Adds 1 class, 1 field per argument and
       * 3 methods (1 constructor + 1 accessor + 1 bridge) + (Optional) 1 forwarding method for
       * static interface method desugaring
       *
       * <p>we always assume the worst case and for every invoke dynamic in the bytecode, augment
       * the dex references by the max possible fields/methods. At worst, we will end up over
       * estimating slightly, but real world test has shown that the difference is very small to
       * notice.
       */
      public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        registerMethodHandleType(bsm);

        String uid =
            String.valueOf(DalvikMemberReference.of(bsm.getOwner(), name, desc).hashCode());
        createAdditionalFieldsForDesugar(uid + String.valueOf(bsm.hashCode()));
        for (Object arg : bsmArgs) {
          if (arg instanceof Handle) {
            registerMethodHandleType((Handle) arg);
          }
          createAdditionalFieldsForDesugar(uid + String.valueOf(arg.hashCode()));
        }
      }

      @Override
      public void visitMultiANewArrayInsn(String desc, int dims) {
        // dx translates this instruction into a method invocation on
        // Array.newInstance(Class clazz, int...dims);
        methodReferenceBuilder.add(
            DalvikMemberReference.of(MULTIARRAY_OWNER, MULTIARRAY_NAME, MULTIARRAY_DESC));
        String boxingType = getBoxingType(Type.getType(desc).getElementType());
        if (boxingType != null) {
          fieldReferenceBuilder.add(
              DalvikMemberReference.of(boxingType, PRIMITIVE_TYPE_FIELD_NAME, MULTIARRAY_ARG_TYPE));
        }
      }

      @Override
      public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return annotationVisitor;
      }

      @Override
      public AnnotationVisitor visitAnnotationDefault() {
        return annotationVisitor;
      }

      @Override
      public AnnotationVisitor visitTypeAnnotation(
          int typeRef, TypePath typePath, String desc, boolean visible) {
        return annotationVisitor;
      }

      @Override
      public AnnotationVisitor visitParameterAnnotation(
          int parameter, String desc, boolean visible) {
        return annotationVisitor;
      }

      @Override
      public AnnotationVisitor visitInsnAnnotation(
          int typeRef, TypePath typePath, String desc, boolean visible) {
        return annotationVisitor;
      }

      @Override
      public AnnotationVisitor visitTryCatchAnnotation(
          int typeRef, TypePath typePath, String desc, boolean visible) {
        return annotationVisitor;
      }

      @Override
      public AnnotationVisitor visitLocalVariableAnnotation(
          int typeRef,
          TypePath typePath,
          Label[] start,
          Label[] end,
          int[] index,
          String desc,
          boolean visible) {
        return annotationVisitor;
      }

      private void registerMethodHandleType(Handle handle) {
        switch (handle.getTag()) {
          case Opcodes.H_GETFIELD:
          case Opcodes.H_GETSTATIC:
          case Opcodes.H_PUTFIELD:
          case Opcodes.H_PUTSTATIC:
            visitFieldInsn(Opcodes.GETFIELD, handle.getOwner(), handle.getName(), handle.getDesc());
            break;
          case Opcodes.H_INVOKEVIRTUAL:
          case Opcodes.H_INVOKEINTERFACE:
          case Opcodes.H_INVOKESPECIAL:
          case Opcodes.H_INVOKESTATIC:
          case Opcodes.H_NEWINVOKESPECIAL:
            visitMethodInsn(
                Opcodes.H_INVOKEVIRTUAL,
                handle.getOwner(),
                handle.getName(),
                handle.getDesc(),
                false);
            createAdditionalMethodsForDesugar(handle);
            break;
          default:
            throw new IllegalStateException(
                "MethodHandle tag is not supported: " + handle.getTag());
        }
      }

      private void createAdditionalMethodsForDesugar(Handle handle) {
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "-$$Lambda$" + handle.getOwner(),
            "lambda$constructor$" + handle.getName(),
            handle.getDesc(),
            false);
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "-$$Lambda$" + handle.getOwner(),
            "lambda$original$" + handle.getName(),
            handle.getDesc(),
            false);
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "-$$Lambda$" + handle.getOwner(),
            "lambda$bridge$" + handle.getName(),
            handle.getDesc(),
            false);
        if (!(handle.getTag() == Opcodes.H_INVOKEVIRTUAL
            || handle.getTag() == Opcodes.H_INVOKEINTERFACE)) {
          visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "-$$Lambda$" + handle.getOwner(),
              "lambda$forwarding$" + handle.getName(),
              handle.getDesc(),
              false);
        }
      }

      private void createAdditionalFieldsForDesugar(String uid) {
        visitFieldInsn(Opcodes.GETFIELD, "-$$Lambda$" + uid, "lambda$field$" + uid, "desugared");
      }
    }

    private class StatsFieldVisitor extends FieldVisitor {
      public StatsFieldVisitor() {
        super(Opcodes.ASM5);
      }

      @Override
      public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return annotationVisitor;
      }

      @Override
      public AnnotationVisitor visitTypeAnnotation(
          int typeRef, TypePath typePath, String desc, boolean visible) {
        return annotationVisitor;
      }
    }

    private class StatsAnnotationVisitor extends AnnotationVisitor {
      public StatsAnnotationVisitor() {
        super(Opcodes.ASM5);
      }

      @Override
      public AnnotationVisitor visitAnnotation(String name, String desc) {
        return this;
      }

      @Override
      public AnnotationVisitor visitArray(String name) {
        return this;
      }

      @Override
      public void visitEnum(String name, String desc, String value) {
        String ownerName = Type.getType(desc).getInternalName();
        fieldReferenceBuilder.add(DalvikMemberReference.of(ownerName, value, desc));
      }
    }
  }
}
