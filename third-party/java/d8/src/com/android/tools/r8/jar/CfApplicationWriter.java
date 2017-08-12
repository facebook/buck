// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.jar;

import static org.objectweb.asm.Opcodes.ASM6;

import com.android.tools.r8.OutputSink;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.utils.InternalOptions;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

public class CfApplicationWriter {
  private final DexApplication application;
  private final InternalOptions options;

  public CfApplicationWriter(DexApplication application, InternalOptions options) {
    this.application = application;
    this.options = options;
  }

  public void write(OutputSink outputSink, ExecutorService executor) throws IOException {
    application.timing.begin("CfApplicationWriter.write");
    try {
      writeApplication(outputSink, executor);
    } finally {
      application.timing.end();
    }
  }

  private void writeApplication(OutputSink outputSink, ExecutorService executor)
      throws IOException {
    for (DexProgramClass clazz : application.classes()) {
      if (clazz.getSynthesizedFrom().isEmpty()) {
        writeClass(clazz, outputSink);
      } else {
        throw new Unimplemented("No support for synthetics in the Java bytecode backend.");
      }
    }
  }

  private void writeClass(DexProgramClass clazz, OutputSink outputSink) throws IOException {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    writer.visitSource(clazz.sourceFile.toString(), null);
    int version = clazz.getClassFileVersion();
    int access = clazz.accessFlags.getAsCfAccessFlags();
    String desc = clazz.type.toDescriptorString();
    String name = internalName(clazz.type);
    String signature = null; // TODO(zerny): Support generic signatures.
    String superName =
        clazz.type == options.itemFactory.objectType ? null : internalName(clazz.superType);
    String[] interfaces = new String[clazz.interfaces.values.length];
    for (int i = 0; i < clazz.interfaces.values.length; i++) {
      interfaces[i] = internalName(clazz.interfaces.values[i]);
    }
    writer.visit(version, access, name, signature, superName, interfaces);
    // TODO(zerny): Methods and fields.
    for (DexEncodedMethod method : clazz.directMethods()) {
      writeMethod(method, writer);
    }
    writer.visitEnd();

    byte[] result = writer.toByteArray();
    assert verifyCf(result);
    outputSink.writeClassFile(result, Collections.singleton(desc), desc);
  }

  private void writeMethod(DexEncodedMethod method, ClassWriter writer) {
    int access = method.accessFlags.getAsCfAccessFlags();
    String name = method.method.name.toString();
    String desc = method.descriptor();
    String signature = null; // TODO(zerny): Support generic signatures.
    String[] exceptions = null;
    MethodVisitor visitor = writer.visitMethod(access, name, desc, signature, exceptions);
    writeCode(method.getCode(), visitor);
  }

  private void writeCode(Code code, MethodVisitor visitor) {
    if (code.isJarCode()) {
      code.asJarCode().writeTo(visitor);
    } else {
      assert code.isCfCode();
      code.asCfCode().write(visitor);
    }
  }

  private static String internalName(DexType type) {
    return Type.getType(type.toDescriptorString()).getInternalName();
  }

  private String printCf(byte[] result) {
    ClassReader reader = new ClassReader(result);
    ClassNode node = new ClassNode(ASM6);
    reader.accept(node, ASM6);
    StringWriter writer = new StringWriter();
    for (MethodNode method : node.methods) {
      TraceMethodVisitor visitor = new TraceMethodVisitor(new Textifier());
      method.accept(visitor);
      visitor.p.print(new PrintWriter(writer));
      writer.append('\n');
    }
    return writer.toString();
  }

  private static boolean verifyCf(byte[] result) {
    ClassReader reader = new ClassReader(result);
    PrintWriter pw = new PrintWriter(System.out);
    CheckClassAdapter.verify(reader, false, pw);
    return true;
  }
}
