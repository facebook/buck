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

package com.facebook.buck.jvm.java.abi.kotlin;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import kotlinx.metadata.Flag;
import kotlinx.metadata.KmDeclarationContainer;
import kotlinx.metadata.jvm.KotlinClassHeader;
import kotlinx.metadata.jvm.KotlinClassMetadata;
import org.objectweb.asm.tree.AnnotationNode;

/** A class to read Kotlin metadata. */
public class KotlinMetadataReader {

  /**
   * Method to find the inline functions of a Kotlin class by finding the Kotlin metadata annotation
   * and reading it.
   */
  public static List<String> getInlineFunctions(List<AnnotationNode> annotations) {
    AnnotationNode annotationNode = findMetadataAnnotation(annotations);
    if (annotationNode == null) {
      return Collections.emptyList();
    }

    KotlinClassMetadata metadata = KotlinClassMetadata.read(createHeader(annotationNode));

    KmDeclarationContainer container;
    if (metadata instanceof KotlinClassMetadata.Class) {
      container = ((KotlinClassMetadata.Class) metadata).toKmClass();
    } else if (metadata instanceof KotlinClassMetadata.FileFacade) {
      container = ((KotlinClassMetadata.FileFacade) metadata).toKmPackage();
    } else if (metadata instanceof KotlinClassMetadata.MultiFileClassPart) {
      container = ((KotlinClassMetadata.MultiFileClassPart) metadata).toKmPackage();
    } else {
      return Collections.emptyList();
    }

    return container.getFunctions().stream()
        .filter(function -> Flag.Function.IS_INLINE.invoke(function.getFlags()))
        .map(function -> function.getName())
        .collect(Collectors.toList());
  }

  @Nullable
  private static AnnotationNode findMetadataAnnotation(List<AnnotationNode> annotations) {
    if (annotations == null) {
      return null;
    }
    return annotations.stream()
        .filter(annotation -> "Lkotlin/Metadata;".equals(annotation.desc))
        .findFirst()
        .orElse(null);
  }

  /**
   * Converts the given AnnotationNode representing the @kotlin.Metadata annotation into
   * KotlinClassHeader, to be able to use it in KotlinClassMetadata.read.
   */
  private static KotlinClassHeader createHeader(AnnotationNode node) {
    Integer kind = null;
    int[] metadataVersion = null;
    int[] bytecodeVersion = null;
    String[] data1 = null;
    String[] data2 = null;
    String extraString = null;
    String packageName = null;
    Integer extraInt = null;

    Iterator<Object> it = node.values.iterator();
    while (it.hasNext()) {
      String name = (String) it.next();
      Object value = it.next();

      switch (name) {
        case "k":
          kind = (Integer) value;
          break;
        case "mv":
          metadataVersion = listToIntArray(value);
          break;
        case "bv":
          bytecodeVersion = listToIntArray(value);
          break;
        case "d1":
          data1 = listToStringArray(value);
          break;
        case "d2":
          data2 = listToStringArray(value);
          break;
        case "xs":
          extraString = (String) value;
          break;
        case "pn":
          packageName = (String) value;
          break;
        case "xi":
          extraInt = (Integer) value;
          break;
      }
    }

    return new KotlinClassHeader(
        kind, metadataVersion, bytecodeVersion, data1, data2, extraString, packageName, extraInt);
  }

  @SuppressWarnings("unchecked")
  private static int[] listToIntArray(Object list) {
    return ((List<Integer>) list).stream().mapToInt(integer -> integer).toArray();
  }

  @SuppressWarnings("unchecked")
  private static String[] listToStringArray(Object list) {
    return ((List<String>) list).toArray(new String[0]);
  }
}
