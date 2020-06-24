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

package com.facebook.buck.intellij.ideabuck.actions.select.detectors

import com.facebook.buck.intellij.ideabuck.actions.select.PotentialTestClass
import com.facebook.buck.intellij.ideabuck.actions.select.PotentialTestFunction
import org.jetbrains.kotlin.asJava.toLightAnnotation
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.refactoring.isAbstract
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.psi.psiUtil.isPublic

/** Implementation of PotentialTestClass - represent KtClass as a potential test class */
class KotlinPotentialTestClass(private val kotlinClass: KtClass) : PotentialTestClass {
  override fun hasSuperClass(superClassQualifiedName: String): Boolean {
    var superClass = kotlinClass.toLightClass()?.superClass
    while (superClass != null) {
      if (superClassQualifiedName == superClass.qualifiedName) {
        return true
      }
      superClass = superClass.superClass
    }
    return false
  }

  override fun hasTestFunction(annotationName: String): Boolean {
    return kotlinClass.declarations.any {
      it is KtNamedFunction &&
          !it.isAbstract() &&
          it.annotationEntries.any { it.toLightAnnotation()?.qualifiedName == annotationName }
    }
  }

  override fun hasAnnotation(annotationName: String): Boolean {
    return kotlinClass.annotationEntries.any {
      it.toLightAnnotation()?.qualifiedName.equals(annotationName)
    }
  }

  override fun isPotentialTestClass() =
      !kotlinClass.isAbstract() && !kotlinClass.isPrivate() && !kotlinClass.isData()
}

/**
 * Implementation of PotentialTestFunction - represent KtNamedFunction as a potential test function
 */
class KotlinPotentialTestFunction(private val kotlinFunction: KtNamedFunction) :
    PotentialTestFunction {
  override fun isJUnit3TestMethod(): Boolean =
      kotlinFunction.isPublic &&
          !kotlinFunction.isAbstract() &&
          !kotlinFunction.hasDeclaredReturnType() &&
          kotlinFunction.typeParameterList == null &&
          kotlinFunction.name?.startsWith("test") ?: false

  override fun getContainingClass(): PotentialTestClass? {
    val containingClass = kotlinFunction.containingClass()
    return if (containingClass != null) KotlinPotentialTestClass(containingClass) else null
  }

  override fun isPotentialTestFunction(): Boolean {
    return !kotlinFunction.isAbstract()
  }

  override fun hasAnnotation(annotationName: String): Boolean {
    return kotlinFunction.annotationEntries.any {
      it.toLightAnnotation()?.qualifiedName.equals(annotationName)
    }
  }
}
