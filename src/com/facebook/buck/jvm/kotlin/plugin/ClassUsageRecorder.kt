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

package com.facebook.buck.jvm.kotlin.plugin

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import kotlin.reflect.jvm.internal.impl.load.java.sources.JavaSourceElement
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.load.java.JavaClassFinder
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.classId
import org.jetbrains.kotlin.load.java.structure.impl.VirtualFileBoundJavaClass
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinder
import org.jetbrains.kotlin.load.kotlin.getSourceElement
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource
import org.jetbrains.kotlin.types.KotlinType

private const val JAR_FILE_SEPARATOR = "!/"

class ClassUsageRecorder(project: Project, module: ModuleDescriptor) {

  private val searchScope = GlobalSearchScope.allScope(project)
  private val psiFacade = KotlinJavaPsiFacade.getInstance(project)
  private val fileFinder = VirtualFileFinder.getInstance(project, module)
  private val seen = mutableSetOf<KotlinType>()
  private val results = mutableMapOf<String, MutableMap<String, Int>>()

  fun getUsage(): Map<String, MutableMap<String, Int>> = HashMap(results)

  fun recordClass(fqName: FqName) {
    if (!fqName.isRoot) {
      recordClass(ClassId.topLevel(fqName))
    }
  }

  fun recordClass(type: KotlinType?) {
    type?.run {
      /**
       * This is done not only for optimisation. The main reason is to avoid endless recursion when
       * a class depends on itself (e.g. `class Foo : Comparable<Foo>` or an annotation is used on
       * itself like [java.lang.annotation.Target])
       */
      if (!seen.add(this)) return

      constructor.declarationDescriptor?.let(::recordClass)

      arguments.forEach {
        if (!it.isStarProjection) {
          recordClass(it.type)
        }
      }
    }
  }

  fun recordClass(descriptor: DeclarationDescriptor) {
    val source = getSourceElement(descriptor)
    when {
      source is JvmPackagePartSource -> {
        source.knownJvmBinaryClass?.location?.let(::addFile)
      }
      source is KotlinJvmBinarySourceElement -> {
        addFile(source.binaryClass.location)
      }
      source is JavaSourceElement && source.javaElement is VirtualFileBoundJavaClass -> {
        (source.javaElement as VirtualFileBoundJavaClass).virtualFile?.path?.let(::addFile)
      }
      descriptor is DescriptorWithContainerSource &&
          descriptor.containerSource is JvmPackagePartSource -> {
        (descriptor.containerSource as JvmPackagePartSource).knownJvmBinaryClass?.location?.let(
            ::addFile)
      }
    }

    descriptor.annotations.forEach { recordClass(it.type) }

    when (descriptor) {
      is ClassDescriptor -> {
        descriptor.classId?.let(::recordClass)
      }
      is ValueDescriptor -> {
        recordClass(descriptor.type)
      }
      is CallableDescriptor -> {
        descriptor.valueParameters.forEach { recordClass(it) }
        descriptor.typeParameters.forEach { recordClass(it) }
        descriptor.returnType?.let(::recordClass)
      }
    }
  }

  private fun recordClass(classId: ClassId) {
    fileFinder.findVirtualFileWithHeader(classId)?.path?.let(::addFile)
    psiFacade.findClass(JavaClassFinder.Request(classId), searchScope)?.let(::recordSupertypes)
  }

  private fun recordSupertypes(javaClass: JavaClass) {
    javaClass.supertypes.forEach { (it.classifier as? JavaClass)?.classId?.let(::recordClass) }
  }

  private fun addFile(path: String) {
    if (path.contains(JAR_FILE_SEPARATOR)) {
      val (jarPath, classPath) = path.split(JAR_FILE_SEPARATOR)
      val occurrences = results.computeIfAbsent(jarPath) { mutableMapOf() }
      occurrences.compute(classPath) { _, count -> count ?: 0 + 1 }
    }
  }
}
