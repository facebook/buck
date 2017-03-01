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

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
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

  public ClassNode getNode() {
    return node;
  }

  @Override
  public int compareTo(ClassMirror o) {
    if (this == o) {
      return 0;
    }

    return fileName.compareTo(o.fileName);
  }
}

