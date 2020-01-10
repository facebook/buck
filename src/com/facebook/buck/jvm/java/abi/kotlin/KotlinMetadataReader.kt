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

package com.facebook.buck.jvm.java.abi.kotlin

import kotlinx.metadata.Flag
import kotlinx.metadata.KmDeclarationContainer
import kotlinx.metadata.jvm.KotlinClassHeader
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.jvm.getterSignature
import kotlinx.metadata.jvm.setterSignature
import kotlinx.metadata.jvm.signature
import org.objectweb.asm.tree.AnnotationNode

/**
 * Method to find the inline functions of a Kotlin class by finding the Kotlin metadata annotation
 * and reading it.
 */
fun getInlineFunctions(annotationNode: AnnotationNode): List<String> {
    val metadata = KotlinClassMetadata.read(createHeader(annotationNode))

    val container: KmDeclarationContainer
    if (metadata is KotlinClassMetadata.Class) {
        container = metadata.toKmClass()
    } else if (metadata is KotlinClassMetadata.FileFacade) {
        container = metadata.toKmPackage()
    } else if (metadata is KotlinClassMetadata.MultiFileClassPart) {
        container = metadata.toKmPackage()
    } else {
        return emptyList()
    }

    val inlineFunctions = container.functions.filter { Flag.Function.IS_INLINE(it.flags) }
        .mapNotNull { it.signature?.name }

    val inlineProperties =
        container.properties.filter { Flag.PropertyAccessor.IS_INLINE(it.flags) }.map { it.name }

    val inlineGetters =
        container.properties.filter { Flag.PropertyAccessor.IS_INLINE(it.getterFlags) }
            .mapNotNull { it.getterSignature?.name }

    val inlineSetters =
        container.properties.filter { Flag.PropertyAccessor.IS_INLINE(it.setterFlags) }
            .mapNotNull { it.setterSignature?.name }

    return inlineFunctions.union(inlineProperties).union(inlineGetters).union(inlineSetters)
        .toList()
}

/**
 * Converts the given AnnotationNode representing the @kotlin.Metadata annotation into
 * KotlinClassHeader, to be able to use it in KotlinClassMetadata.read.
 */
private fun createHeader(node: AnnotationNode): KotlinClassHeader {
    var kind: Int? = null
    var metadataVersion: IntArray? = null
    var bytecodeVersion: IntArray? = null
    var data1: Array<String>? = null
    var data2: Array<String>? = null
    var extraString: String? = null
    var packageName: String? = null
    var extraInt: Int? = null

    val it = node.values.iterator()
    while (it.hasNext()) {
        val name = it.next() as String
        val value = it.next()

        when (name) {
            "k" -> kind = value as Int
            "mv" -> metadataVersion = listToIntArray(value)
            "bv" -> bytecodeVersion = listToIntArray(value)
            "d1" -> data1 = listToStringArray(value)
            "d2" -> data2 = listToStringArray(value)
            "xs" -> extraString = value as String
            "pn" -> packageName = value as String
            "xi" -> extraInt = value as Int
        }
    }

    return KotlinClassHeader(kind, metadataVersion, bytecodeVersion, data1, data2, extraString,
        packageName, extraInt)
}

@SuppressWarnings("unchecked") private fun listToIntArray(list: Any): IntArray {
    return (list as List<Int>).toIntArray()
}

@SuppressWarnings("unchecked") private fun listToStringArray(list: Any): Array<String> {
    return (list as List<String>).toTypedArray()
}
