/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.abi;

import com.facebook.buck.jvm.java.abi.kotlin.KotlinMetadataReaderKt;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

class StubJarClassEntry extends StubJarEntry {
  private static final String DEFAULT_METHOD_SUFFIX = "$default";
  private static final int DEFAULT_METHOD_SUFFIX_LENGTH = DEFAULT_METHOD_SUFFIX.length();

  @Nullable private final Set<String> referencedClassNames;
  private final List<String> methodBodiesToRetain;
  private final Path path;
  private final ClassNode stub;
  private final boolean retainEverything;
  private final boolean isKotlinClass;

  @Nullable
  public static StubJarClassEntry of(
      LibraryReader input,
      Path path,
      @Nullable AbiGenerationMode compatibilityMode,
      boolean isKotlinModule,
      Map<String, List<String>> inlineFunctionsMap)
      throws IOException {
    ClassNode stub = new ClassNode(Opcodes.ASM7);

    // Kotlin has the concept of "inline functions", which means that we need to retain the body
    // of these functions so that the compiler is able to inline them.
    List<String> methodBodiesToRetain = Collections.emptyList();
    boolean isKotlinClass = false;
    boolean retainAllMethodBodies = false;

    if (isKotlinModule) {
      AnnotationNode kotlinMetadataAnnotation = findKotlinMetadataAnnotation(input, path);
      if (kotlinMetadataAnnotation != null) {
        isKotlinClass = true;
        if (path.toString().contains("$sam$i")) {
          // These classes are created when we have a Single Abstract Method (SAM) interface that is
          // used within an inline function, and in these cases we need to retain the whole class.
          input.visitClass(path, stub, false);
          return new StubJarClassEntry(
              path, stub, Collections.emptySet(), Collections.emptyList(), true, isKotlinClass);
        }
        ClassNode dummyStub = new ClassNode(Opcodes.ASM7);
        input.visitClass(path, dummyStub, true);
        retainAllMethodBodies =
            retainAllMethodBodies(
                inlineFunctionsMap, path, dummyStub.outerClass, dummyStub.outerMethod);
        if (retainAllMethodBodies) {
          methodBodiesToRetain =
              dummyStub.methods.stream()
                  .map(methodNode -> methodNode.name)
                  .collect(Collectors.toList());
        } else {
          methodBodiesToRetain =
              KotlinMetadataReaderKt.getInlineFunctions(kotlinMetadataAnnotation);
        }
      }
    }

    // As we read the class in, we create a partial stub that removes non-ABI methods and fields
    // but leaves the entire InnerClasses table. We record all classes that are referenced from
    // ABI methods and fields, and will use that information later to filter the InnerClasses table.
    ClassReferenceTracker referenceTracker = new ClassReferenceTracker(stub);
    ClassVisitor firstLevelFiltering =
        new AbiFilteringClassVisitor(referenceTracker, methodBodiesToRetain, null, isKotlinClass);

    // If we want ABIs that are compatible with those generated from source, we add a visitor
    // at the very start of the chain which transforms the event stream coming out of `ClassNode`
    // to look like what ClassVisitorDriverFromElement would have produced.
    if (compatibilityMode != null && compatibilityMode != AbiGenerationMode.CLASS) {
      firstLevelFiltering = new SourceAbiCompatibleVisitor(firstLevelFiltering, compatibilityMode);
    }
    input.visitClass(path, firstLevelFiltering, /* skipCode */ !isKotlinClass);

    // The synthetic package-info class is how package annotations are recorded; that one is
    // actually used by the compiler
    // Kotlin top functions reside in synthetic classes, we should output ABIs for them.
    if ((isSyntheticClass(stub) && isKotlinModule)
        || !(isSyntheticClass(stub) || isAnonymousOrLocalClass(stub))
        || retainAllMethodBodies
        || stub.name.endsWith("/package-info")) {
      return new StubJarClassEntry(
          path, stub, referenceTracker.getReferencedClassNames(), methodBodiesToRetain, false, isKotlinClass);
    }

    return null;
  }

  private StubJarClassEntry(
      Path path,
      ClassNode stub,
      Set<String> referencedClassNames,
      List<String> methodBodiesToRetain,
      boolean retainEverything,
      boolean isKotlinClass) {
    this.path = path;
    this.stub = stub;
    this.referencedClassNames = referencedClassNames;
    this.methodBodiesToRetain = methodBodiesToRetain;
    this.retainEverything = retainEverything;
    this.isKotlinClass = isKotlinClass;
  }

  @Override
  public void write(StubJarWriter writer) {
    writer.writeEntry(path, this::openInputStream);
  }

  @Override
  public List<String> getInlineMethods() {
    return methodBodiesToRetain;
  }

  private InputStream openInputStream() {
    ClassWriter writer = new ClassWriter(0);
    ClassVisitor visitor = writer;
    if (!retainEverything) {
      visitor = new InnerClassSortingClassVisitor(stub.name, visitor);
      visitor = new AbiFilteringClassVisitor(visitor, methodBodiesToRetain, referencedClassNames, isKotlinClass);
    }

    stub.accept(visitor);

    return new ByteArrayInputStream(writer.toByteArray());
  }

  private static boolean isSyntheticClass(ClassNode node) {
    return ((node.access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC);
  }

  private static boolean isAnonymousOrLocalClass(ClassNode node) {
    InnerClassNode innerClass = getInnerClassMetadata(node);
    while (innerClass != null) {
      if (innerClass.outerName == null) {
        return true;
      }
      innerClass = getInnerClassMetadata(node, innerClass.outerName);
    }

    return false;
  }

  /**
   * If this is a class that was created for a method that needs to be inlined, then we need to make
   * sure that we retain its methods.
   */
  private static boolean retainAllMethodBodies(
      Map<String, List<String>> inlineFunctionsMap,
      Path path,
      String outerClass,
      String outerMethod) {
    if (path.toString().contains("$$inlined$")) {
      // These classes are created when a function calls an inline function with a crossinline
      // parameter.
      return true;
    }

    final List<String> inlineFunctions = inlineFunctionsMap.get(outerClass);
    if (inlineFunctions == null || outerMethod == null) {
      return false;
    }

    return inlineFunctions.contains(sanitizeMethodName(outerMethod));
  }

  private static String sanitizeMethodName(String methodName) {
    if (methodName.endsWith(DEFAULT_METHOD_SUFFIX)) {
      return methodName.substring(0, methodName.length() - DEFAULT_METHOD_SUFFIX_LENGTH);
    }

    return methodName;
  }

  @Nullable
  private static InnerClassNode getInnerClassMetadata(ClassNode node) {
    String name = node.name;
    return getInnerClassMetadata(node, name);
  }

  @Nullable
  private static AnnotationNode findKotlinMetadataAnnotation(LibraryReader input, Path relativePath)
      throws IOException {
    final List<AnnotationNode> annotations = getVisibleAnnotations(input, relativePath);
    if (annotations == null) {
      return null;
    }
    return annotations.stream()
        .filter(annotation -> "Lkotlin/Metadata;".equals(annotation.desc))
        .findFirst()
        .orElse(null);
  }

  private static List<AnnotationNode> getVisibleAnnotations(LibraryReader input, Path relativePath)
      throws IOException {
    ClassNode node = new ClassNode();
    input.visitClass(relativePath, node, /* skipCode */ true);

    return node.visibleAnnotations;
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
    private final List<String> nestMembers = new ArrayList<>();

    private InnerClassSortingClassVisitor(String className, ClassVisitor cv) {
      super(Opcodes.ASM7, cv);
      this.className = className;
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      innerClasses.add(new InnerClassNode(name, outerName, innerName, access));
    }

    @Override
    public void visitNestMember(String nestMember) {
      nestMembers.add(nestMember);
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

      nestMembers.stream().sorted().forEach(nestMember -> cv.visitNestMember(nestMember));

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
