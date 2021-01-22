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

package com.facebook.buck.intellij.ideabuck.actions.select

import com.facebook.buck.intellij.ideabuck.icons.BuckIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Implementation of {@link KotlinBuckTestAction} for class test action. */
class ClassKotlinBuckTestAction(testClass: KtClass, debug: Boolean) :
    KotlinBuckTestAction(testClass, debug) {
  override fun getTestSelector(): String = testClass.fqName?.asString() ?: ""
  override fun getFullTestName(): String = testClass.name ?: ""
  override fun getDisplayTestName(): String = getFullTestName()
}

/** Implementation of {@link KotlinBuckTestAction} for function test action. */
class FunctionKotlinBuckTestAction(
    private val testFunction: KtNamedFunction, testClass: KtClass, debug: Boolean
) : KotlinBuckTestAction(testClass, debug) {

  override fun getTestSelector(): String = testClass.fqName?.asString() + "#" + testFunction.name
  override fun getFullTestName(): String = testClass.name + "#" + testFunction.name
  override fun getDisplayTestName(): String = truncateName(testFunction.name)
}

/** Implementation of {@link AbstractBuckTestAction} that runs a class/method. */
abstract class KotlinBuckTestAction(val testClass: KtClass, private val debug: Boolean) :
    AbstractBuckTestAction() {
  override fun isDebug(): Boolean {
    return debug
  }

  /** User press the run/debug icon */
  override fun actionPerformed(e: AnActionEvent) {
    val project = testClass.project
    val virtualFile = testClass.containingFile.virtualFile
    createTestConfigurationFromContext(getFullTestName(), getTestSelector(), project, virtualFile)
  }

  /** Update the run/debug gutter icon */
  override fun update(e: AnActionEvent) {
    val verb =
        if (debug) {
            e.presentation.icon = BuckIcons.BUCK_DEBUG
            "Debug"
        } else {
            e.presentation.icon = BuckIcons.BUCK_RUN
            "Run"
        }
    e.presentation.text = "$verb '${getDisplayTestName()}' with Buck"
    e.presentation.isEnabledAndVisible = true
  }

  // Utils function to distinguish function test action from class test action
  abstract fun getTestSelector(): String
  abstract fun getFullTestName(): String
  abstract fun getDisplayTestName(): String
}
