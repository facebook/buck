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

import com.facebook.buck.intellij.ideabuck.actions.select.detectors.KotlinPotentialTestClass
import com.facebook.buck.intellij.ideabuck.actions.select.detectors.KotlinPotentialTestFunction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClass

/**
 * Creates runnable line markers (gutter icon) for classes/methods that can be run by a buck test
 * configurations.
 */
class KotlinRunLineMarkerContributor : RunLineMarkerContributor() {

  override fun getInfo(psiElement: PsiElement): Info? {

    if (psiElement is LeafPsiElement && psiElement.elementType === KtTokens.IDENTIFIER) {
      when (val parent = psiElement.parent
      ) {
        is KtClass -> {

          val isTestClass = BuckKotlinTestDetector.isTestClass(KotlinPotentialTestClass(parent))
          if (isTestClass) {
            val runAction = ClassKotlinBuckTestAction(parent, false)
            val debugAction = ClassKotlinBuckTestAction(parent, true)
            return buildInfo(runAction, debugAction)
          }
        }
        is KtNamedFunction -> {

          val containingClass = parent.containingClass() ?: return null
          val isTestFunction =
              BuckKotlinTestDetector.isTestFunction(KotlinPotentialTestFunction(parent))
          if (isTestFunction) {
            val runAction = FunctionKotlinBuckTestAction(parent, containingClass, false)
            val debugAction = FunctionKotlinBuckTestAction(parent, containingClass, true)
            return buildInfo(runAction, debugAction)
          }
        }
      }
    }
    return null
  }

  private fun buildInfo(runAction: KotlinBuckTestAction, debugAction: KotlinBuckTestAction): Info {
    return Info(
        IconLoader.getIcon("/icons/buck_icon.png"),
        arrayOf(runAction, debugAction),
        RUN_TEST_TOOLTIP_PROVIDER)
  }
}
