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

package com.facebook.buck.jvm.java.abi.kotlin;

import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import kotlinx.metadata.Flag;
import kotlinx.metadata.KmDeclarationContainer;
import kotlinx.metadata.KmProperty;
import kotlinx.metadata.jvm.JvmExtensionsKt;
import kotlinx.metadata.jvm.JvmMethodSignature;
import kotlinx.metadata.jvm.KotlinClassHeader;
import kotlinx.metadata.jvm.KotlinClassMetadata;
import org.objectweb.asm.tree.AnnotationNode;

/** Utilities to read Kotlin class metadata. */
public class KotlinMetadataReader {

  /**
   * Method to find the inline functions of a Kotlin class by finding the Kotlin metadata annotation
   * and reading it.
   */
  public static ImmutableList<String> getInlineFunctions(AnnotationNode annotationNode) {
    KotlinClassMetadata metadata = KotlinClassMetadata.read(createHeader(annotationNode));

    KmDeclarationContainer container;
    if (metadata instanceof KotlinClassMetadata.Class) {
      container = ((KotlinClassMetadata.Class) metadata).toKmClass();
    } else if (metadata instanceof KotlinClassMetadata.FileFacade) {
      container = ((KotlinClassMetadata.FileFacade) metadata).toKmPackage();
    } else if (metadata instanceof KotlinClassMetadata.MultiFileClassPart) {
      container = ((KotlinClassMetadata.MultiFileClassPart) metadata).toKmPackage();
    } else {
      return ImmutableList.of();
    }

    ImmutableList<String> inlineFunctions =
        container.getFunctions().stream()
            .filter(it -> Flag.Function.IS_INLINE.invoke(it.getFlags()))
            .map(JvmExtensionsKt::getSignature)
            .filter(Objects::nonNull)
            .map(JvmMethodSignature::getName)
            .collect(ImmutableList.toImmutableList());

    ImmutableList<String> inlineProperties =
        container.getProperties().stream()
            .filter(it -> Flag.PropertyAccessor.IS_INLINE.invoke(it.getFlags()))
            .map(KmProperty::getName)
            .collect(ImmutableList.toImmutableList());

    ImmutableList<String> inlineGetters =
        container.getProperties().stream()
            .filter(it -> Flag.PropertyAccessor.IS_INLINE.invoke(it.getGetterFlags()))
            .map(JvmExtensionsKt::getGetterSignature)
            .filter(Objects::nonNull)
            .map(JvmMethodSignature::getName)
            .collect(ImmutableList.toImmutableList());

    ImmutableList<String> inlineSetters =
        container.getProperties().stream()
            .filter(it -> Flag.PropertyAccessor.IS_INLINE.invoke(it.getSetterFlags()))
            .map(JvmExtensionsKt::getSetterSignature)
            .filter(Objects::nonNull)
            .map(JvmMethodSignature::getName)
            .collect(ImmutableList.toImmutableList());

    return Stream.of(inlineFunctions, inlineProperties, inlineGetters, inlineSetters)
        .flatMap(Collection::stream)
        .distinct()
        .sorted()
        .collect(ImmutableList.toImmutableList());
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
    return ((List<Integer>) list).stream().mapToInt(i -> i).toArray();
  }

  @SuppressWarnings("unchecked")
  private static String[] listToStringArray(Object list) {
    return ((List<String>) list).toArray(new String[0]);
  }
}
