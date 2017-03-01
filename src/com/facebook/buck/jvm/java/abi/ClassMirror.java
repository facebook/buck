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

package com.facebook.buck.jvm.java.abi;

import com.google.common.io.ByteSource;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

import javax.annotation.Nullable;

class ClassMirror extends ClassVisitor implements Comparable<ClassMirror> {

  private final String fileName;
  private final ClassNode node;

  public ClassMirror(String name) {
    this(name, new ClassNode());
  }

  private ClassMirror(String name, ClassNode node) {
    super(Opcodes.ASM5, new AbiFilteringClassVisitor(node));
    this.fileName = name;
    this.node = node;
  }

  @Override
  public int compareTo(ClassMirror o) {
    if (this == o) {
      return 0;
    }

    return fileName.compareTo(o.fileName);
  }

  public boolean isAnonymousOrLocalClass() {
    InnerClassNode innerClass = getInnerClassMetadata();
    if (innerClass == null) {
      return false;
    }

    return innerClass.outerName == null;
  }

  @Nullable
  private InnerClassNode getInnerClassMetadata() {
    for (InnerClassNode innerClass : node.innerClasses) {
      if (innerClass.name.equals(node.name)) {
        return innerClass;
      }
    }

    return null;
  }

  public ByteSource getStubClassBytes() {
    ClassWriter writer = new ClassWriter(0);

    node.accept(new AbiFilteringClassVisitor(writer));

    return ByteSource.wrap(writer.toByteArray());
  }
}

