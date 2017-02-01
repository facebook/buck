/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.Elements;

class ClassVisitorDriverFromElement {
  private final DescriptorFactory descriptorFactory;
  private final SignatureFactory signatureFactory;
  private final SourceVersion targetVersion;

  private final ElementVisitor<Void, ClassVisitor> elementVisitorAdapter =
      new ElementScanner8<Void, ClassVisitor>() {
        //boolean classVisitorStarted = false;

        @Override
        public Void visitType(TypeElement e, ClassVisitor visitor) {
          // TODO(jkeljo): Skip anonymous and local
          // TODO(jkeljo): inner class

          visitor.visit(
              sourceVersionToClassFileVersion(targetVersion),
              AccessFlags.getAccessFlags(e),
              descriptorFactory.getInternalName(e),
              signatureFactory.getSignature(e),
              descriptorFactory.getInternalName(e.getSuperclass()),
              e.getInterfaces().stream()
                  .map(descriptorFactory::getInternalName)
                  .toArray(size -> new String[size]));

          // TODO(jkeljo): outer class
          // TODO(jkeljo): annotations

          super.visitType(e, visitor);

          return null;
        }

        @Override
        public Void visitExecutable(ExecutableElement e, ClassVisitor visitor) {
          // TODO(jkeljo): Skip privates for efficiency (ClassMirror already skips them too)

          String[] exceptions = null;  // TODO(jkeljo): Handle throws

          MethodVisitor methodVisitor = visitor.visitMethod(
              AccessFlags.getAccessFlags(e),
              e.getSimpleName().toString(),
              descriptorFactory.getDescriptor(e),
              signatureFactory.getSignature(e),
              exceptions);

          // TODO(jkeljo): parameters
          // TODO(jkeljo): type parameters
          // TODO(jkeljo): annotations

          methodVisitor.visitEnd();

          return null;
        }

        // TODO(jkeljo): fields

      };

  /**
   * @param targetVersion the class file version to target, expressed as the corresponding Java
   *                      source version
   */
  ClassVisitorDriverFromElement(SourceVersion targetVersion, Elements elements) {
    this.targetVersion = targetVersion;
    descriptorFactory = new DescriptorFactory(elements);
    signatureFactory = new SignatureFactory(descriptorFactory);
  }

  public void driveVisitor(TypeElement fullClass, ClassVisitor visitor) throws IOException {
    fullClass.accept(elementVisitorAdapter, visitor);
    visitor.visitEnd();
  }

  /**
   * Gets the class file version corresponding to the given source version constant.
   */
  private static int sourceVersionToClassFileVersion(SourceVersion version) {
    switch (version) {
      case RELEASE_0:
        return Opcodes.V1_1;  // JVMS8 4.1: 1.0 and 1.1 both support version 45.3 (Opcodes.V1_1)
      case RELEASE_1:
        return Opcodes.V1_1;
      case RELEASE_2:
        return Opcodes.V1_2;
      case RELEASE_3:
        return Opcodes.V1_3;
      case RELEASE_4:
        return Opcodes.V1_4;
      case RELEASE_5:
        return Opcodes.V1_5;
      case RELEASE_6:
        return Opcodes.V1_6;
      case RELEASE_7:
        return Opcodes.V1_7;
      case RELEASE_8:
        return Opcodes.V1_8;
      default:
        throw new IllegalArgumentException(String.format("Unexpected source version: %s", version));
    }
  }
}
