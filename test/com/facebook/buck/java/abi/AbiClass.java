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

package com.facebook.buck.java.abi;

import static org.junit.Assert.fail;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AbiClass {

  private final ClassNode classNode;

  private AbiClass(ClassNode classNode) {
    this.classNode = classNode;
  }

  public ClassNode getClassNode() {
    return classNode;
  }

  public MethodNode findMethod(String methodName) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals(methodName)) {
        return method;
      }
    }
    fail("Unable to find method with name: " + methodName);
    return null;
  }

  public FieldNode findField(String fieldName) {
    for (FieldNode field : classNode.fields) {
      if (field.name.equals(fieldName)) {
        return field;
      }
    }

    fail("Unable to find field with name: " + fieldName);
    return null;
  }

  public static AbiClass extract(Path pathToJar, String className) throws IOException {
    try (ZipFile zip = new ZipFile(pathToJar.toString())) {
      ZipEntry entry = zip.getEntry(className);
      try (InputStream entryStream = zip.getInputStream(entry)) {
        ClassReader reader = new ClassReader(entryStream);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        return new AbiClass(classNode);
      }
    }
  }
}
