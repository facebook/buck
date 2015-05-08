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

import com.android.common.annotations.Nullable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A static analyzer to find uses of reference equality checks on the RegisterSpec class,
 * including its superclasses, interfaces, superclass interfaces, interface superinterfaces...
 */
public class RegisterSpecAnalyzer {

  // TODO(user): Infer this automatically.
  private static final ImmutableSet<String> RISKY_TYPES = ImmutableSet.of(
      "com/android/dx/rop/code/RegisterSpec",
      "com/android/dx/rop/type/TypeBearer",
      "com/android/dx/util/ToHuman",
      "java/lang/Comparable",
      "java/lang/Object");

  /**
   * All classes under analysis.
   */
  private final ImmutableMap<String, ClassNode> allClasses;

  /**
   * Log of messages.
   */
  private List<String> log = new ArrayList<>();

  public RegisterSpecAnalyzer(ImmutableMap<String, ClassNode> allClasses) {
    this.allClasses = allClasses;
  }

  public static RegisterSpecAnalyzer analyze(
      ImmutableMap<String, ClassNode> allClasses) {
    RegisterSpecAnalyzer analyzer = new RegisterSpecAnalyzer(allClasses);
    analyzer.go();
    return analyzer;
  }

  public ImmutableList<String> getLog() {
    return ImmutableList.copyOf(log);
  }

  private void go() {
    for (ClassNode klass : allClasses.values()) {
      klass.accept(new RegSpecClassVisitor());
    }
  }

  private class RegSpecClassVisitor extends ClassVisitor {
    @Nullable private String className;

    public RegSpecClassVisitor() {
      super(Opcodes.ASM5);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      super.visit(version, access, name, signature, superName, interfaces);

      Preconditions.checkState(this.className == null);
      this.className = name;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      RegSpecMethodVisitor mv = new RegSpecMethodVisitor(className, name);
      AnalyzerAdapter adapter = new AnalyzerAdapter(className, access, name, desc, mv);
      mv.adapter = adapter;
      return adapter;
    }
  }

  private class RegSpecMethodVisitor extends MethodVisitor {
    private final String className;
    private final String methodName;
    @Nullable AnalyzerAdapter adapter;

    public RegSpecMethodVisitor(String className, String methodName) {
      super(Opcodes.ASM4);
      this.className = className;
      this.methodName = methodName;
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      // There are only two opcodes for comparing object references.
      if (opcode != Opcodes.IF_ACMPEQ && opcode != Opcodes.IF_ACMPNE) {
        return;
      }
      // There should be two arguments on the stack.
      Preconditions.checkState(adapter.stack.size() >= 2);
      Object val1 = adapter.stack.get(adapter.stack.size() - 2);
      Object val2 = adapter.stack.get(adapter.stack.size() - 1);
      // If either is known null, then the comparison is benign.
      if (val1 == Opcodes.NULL || val2 == Opcodes.NULL) {
        return;
      }
      // If not, they both should be strings (class names).
      Preconditions.checkState(val1 instanceof String);
      Preconditions.checkState(val2 instanceof String);
      // If both are risky, report.
      if (RISKY_TYPES.contains(val1) && RISKY_TYPES.contains(val2)) {
        log.add(String.format((Locale) null, "RegisterSpec comparison: %s.%s",
                className, methodName));
      }
    }
  }
}
