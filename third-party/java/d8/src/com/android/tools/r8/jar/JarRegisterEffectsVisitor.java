// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.jar;

import static org.objectweb.asm.Opcodes.ASM6;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.JarApplicationReader;
import com.android.tools.r8.graph.UseRegistry;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class JarRegisterEffectsVisitor extends MethodVisitor {
  private final DexType clazz;
  private final UseRegistry registry;
  private final JarApplicationReader application;

  public JarRegisterEffectsVisitor(DexType clazz, UseRegistry registry,
      JarApplicationReader application) {
    super(ASM6);
    this.clazz = clazz;
    this.registry = registry;
    this.application = application;
  }

  @Override
  public void visitTypeInsn(int opcode, String name) {
    DexType type = application.getTypeFromName(name);
    if (opcode == org.objectweb.asm.Opcodes.NEW) {
      registry.registerNewInstance(type);
    } else {
      registry.registerTypeReference(type);
    }
  }

  @Override
  public void visitMultiANewArrayInsn(String desc, int dims) {
    registry.registerTypeReference(application.getTypeFromDescriptor(desc));
  }

  @Override
  public void visitLdcInsn(Object cst) {
    if (cst instanceof Type) {
      // Nothing to register for method type, it represents only a prototype not associated with a
      // method name.
      if (((Type) cst).getSort() != Type.METHOD) {
        registry.registerConstClass(application.getType((Type) cst));
      }
    } else if (cst instanceof Handle) {
      registerMethodHandleType((Handle) cst);
    }
  }

  @Override
  public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
    DexType ownerType = application.getTypeFromName(owner);
    DexMethod method = application.getMethod(ownerType, name, desc);
    switch (opcode) {
      case Opcodes.INVOKEVIRTUAL:
        registry.registerInvokeVirtual(method);
        break;
      case Opcodes.INVOKESTATIC:
        registry.registerInvokeStatic(method);
        break;
      case Opcodes.INVOKEINTERFACE:
        registry.registerInvokeInterface(method);
        break;
      case Opcodes.INVOKESPECIAL:
        if (name.equals(Constants.INSTANCE_INITIALIZER_NAME) || ownerType == clazz) {
          registry.registerInvokeDirect(method);
        } else {
          registry.registerInvokeSuper(method);
        }
        break;
      default:
        throw new Unreachable("Unexpected opcode " + opcode);
    }
  }

  @Override
  public void visitFieldInsn(int opcode, String owner, String name, String desc) {
    DexField field = application.getField(owner, name, desc);
    switch (opcode) {
      case Opcodes.GETFIELD:
        registry.registerInstanceFieldRead(field);
        break;
      case Opcodes.PUTFIELD:
        registry.registerInstanceFieldWrite(field);
        break;
      case Opcodes.GETSTATIC:
        registry.registerStaticFieldRead(field);
        break;
      case Opcodes.PUTSTATIC:
        registry.registerStaticFieldWrite(field);
        break;
      default:
        throw new Unreachable("Unexpected opcode " + opcode);
    }
  }

  @Override
  public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
    registerMethodHandleType(bsm);

    // Register bootstrap method arguments, only Type and MethodHandle need to be register.
    for (Object arg : bsmArgs) {
      if (arg instanceof Type && ((Type) arg).getSort() == Type.OBJECT) {
        registry.registerTypeReference(application.getType((Type) arg));
      } else if (arg instanceof Handle) {
        registerMethodHandleType((Handle) arg);
      }
    }
  }

  private void registerMethodHandleType(Handle handle) {
    switch (handle.getTag()) {
      case Opcodes.H_GETFIELD:
        visitFieldInsn(Opcodes.GETFIELD, handle.getOwner(), handle.getName(), handle.getDesc());
        break;
      case Opcodes.H_GETSTATIC:
        visitFieldInsn(Opcodes.GETSTATIC, handle.getOwner(), handle.getName(), handle.getDesc());
        break;
      case Opcodes.H_PUTFIELD:
        visitFieldInsn(Opcodes.PUTFIELD, handle.getOwner(), handle.getName(), handle.getDesc());
        break;
      case Opcodes.H_PUTSTATIC:
        visitFieldInsn(Opcodes.PUTSTATIC, handle.getOwner(), handle.getName(), handle.getDesc());
        break;
      case Opcodes.H_INVOKEVIRTUAL:
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, handle.getOwner(), handle.getName(), handle.getDesc(), false);
        break;
      case Opcodes.H_INVOKEINTERFACE:
        visitMethodInsn(
            Opcodes.INVOKEINTERFACE, handle.getOwner(), handle.getName(), handle.getDesc(), true);
        break;
      case Opcodes.H_INVOKESPECIAL:
        visitMethodInsn(
            Opcodes.INVOKESPECIAL, handle.getOwner(), handle.getName(), handle.getDesc(), false);
        break;
      case Opcodes.H_INVOKESTATIC:
        visitMethodInsn(
            Opcodes.INVOKESTATIC, handle.getOwner(), handle.getName(), handle.getDesc(), false);
        break;
      case Opcodes.H_NEWINVOKESPECIAL:
        visitMethodInsn(
            Opcodes.INVOKESPECIAL, handle.getOwner(), handle.getName(), handle.getDesc(), false);
        break;
      default:
        throw new Unreachable("MethodHandle tag is not supported: " + handle.getTag());
    }
  }
}
