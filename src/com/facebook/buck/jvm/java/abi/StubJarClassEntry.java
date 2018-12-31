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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

class StubJarClassEntry extends StubJarEntry {
  @Nullable private final Set<String> referencedClassNames;
  private final Path path;
  private final ClassNode stub;

  @Nullable
  public static StubJarClassEntry of(
      LibraryReader input, Path path, @Nullable AbiGenerationMode compatibilityMode)
      throws IOException {
    ClassNode stub = new ClassNode(Opcodes.ASM7);

    // As we read the class in, we create a partial stub that removes non-ABI methods and fields
    // but leaves the entire InnerClasses table. We record all classes that are referenced from
    // ABI methods and fields, and will use that information later to filter the InnerClasses table.
    ClassReferenceTracker referenceTracker = new ClassReferenceTracker(stub);
    ClassVisitor firstLevelFiltering = new AbiFilteringClassVisitor(referenceTracker);

    // If we want ABIs that are compatible with those generated from source, we add a visitor
    // at the very start of the chain which transforms the event stream coming out of `ClassNode`
    // to look like what ClassVisitorDriverFromElement would have produced.
    if (compatibilityMode != null && compatibilityMode != AbiGenerationMode.CLASS) {
      firstLevelFiltering = new SourceAbiCompatibleVisitor(firstLevelFiltering, compatibilityMode);
    }
    input.visitClass(path, firstLevelFiltering);

    // The synthetic package-info class is how package annotations are recorded; that one is
    // actually used by the compiler
    if (!isAnonymousOrLocalOrSyntheticClass(stub) || stub.name.endsWith("/package-info")) {
      return new StubJarClassEntry(path, stub, referenceTracker.getReferencedClassNames());
    }

    return null;
  }

  private StubJarClassEntry(Path path, ClassNode stub, Set<String> referencedClassNames) {
    this.path = path;
    this.stub = stub;
    this.referencedClassNames = referencedClassNames;
  }

  @Override
  public void write(StubJarWriter writer) {
    writer.writeEntry(path, this::openInputStream);
  }

  private InputStream openInputStream() {
    ClassWriter writer = new ClassWriter(0);
    ClassVisitor visitor = writer;
    visitor = new InnerClassSortingClassVisitor(stub.name, visitor);
    visitor = new AbiFilteringClassVisitor(visitor, referencedClassNames);
    stub.accept(visitor);

    return new ByteArrayInputStream(writer.toByteArray());
  }

  private static boolean isAnonymousOrLocalOrSyntheticClass(ClassNode node) {
    if ((node.access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC) {
      return true;
    }

    InnerClassNode innerClass = getInnerClassMetadata(node);
    while (innerClass != null) {
      if (innerClass.outerName == null) {
        return true;
      }
      innerClass = getInnerClassMetadata(node, innerClass.outerName);
    }

    return false;
  }

  @Nullable
  private static InnerClassNode getInnerClassMetadata(ClassNode node) {
    String name = node.name;
    return getInnerClassMetadata(node, name);
  }

  @Nullable
  private static InnerClassNode getInnerClassMetadata(ClassNode node, String className) {
    for (InnerClassNode innerClass : node.innerClasses) {
      if (innerClass.name.equals(className)) {
        return innerClass;
      }
    }

    return null;
  }

  private static class InnerClassSortingClassVisitor extends ClassVisitor {
    private final String className;
    private final List<InnerClassNode> innerClasses = new ArrayList<>();

    private InnerClassSortingClassVisitor(String className, ClassVisitor cv) {
      super(Opcodes.ASM7, cv);
      this.className = className;
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      innerClasses.add(new InnerClassNode(name, outerName, innerName, access));
    }

    @Override
    public void visitEnd() {
      innerClasses.sort(
          (o1, o2) -> {
            // Enclosing classes and member classes should come first, with their order preserved
            boolean o1IsEnclosingOrMember = isEnclosingOrMember(o1);
            boolean o2IsEnclosingOrMember = isEnclosingOrMember(o2);
            if (o1IsEnclosingOrMember && o2IsEnclosingOrMember) {
              // Preserve order among these
              return 0;
            } else if (o1IsEnclosingOrMember) {
              return -1;
            } else if (o2IsEnclosingOrMember) {
              return 1;
            }

            // References to other classes get sorted.
            return o1.name.compareTo(o2.name);
          });

      for (InnerClassNode innerClass : innerClasses) {
        innerClass.accept(cv);
      }

      super.visitEnd();
    }

    private boolean isEnclosingOrMember(InnerClassNode innerClass) {
      if (className.equals(innerClass.name)) {
        // Self!
        return true;
      }

      if (className.equals(innerClass.outerName)) {
        // Member class
        return true;
      }

      // Enclosing class
      return className.startsWith(innerClass.name + "$");
    }
  }
}
