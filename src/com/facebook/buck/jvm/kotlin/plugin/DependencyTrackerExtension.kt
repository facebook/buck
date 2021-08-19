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

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import java.io.File
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension

class DependencyTrackerExtension(private val outputPath: String) : AnalysisHandlerExtension {

  override fun doAnalysis(
      project: Project,
      module: ModuleDescriptor,
      projectContext: ProjectContext,
      files: Collection<KtFile>,
      bindingTrace: BindingTrace,
      componentProvider: ComponentProvider
  ): AnalysisResult? = null

  override fun analysisCompleted(
      project: Project,
      module: ModuleDescriptor,
      bindingTrace: BindingTrace,
      files: Collection<KtFile>
  ): AnalysisResult? {
    val classUsageRecorder = ClassUsageRecorder(project, module)

    // Slices in [BindingContext] contain information about everything that was used in compilation.
    // We need to inspect a few slices to extract this information.
    bindingTrace.bindingContext.getSliceContents(BindingContext.EXPRESSION_TYPE_INFO)
        .values
        .forEach { classUsageRecorder.recordClass(it.type) }

    bindingTrace.bindingContext.getSliceContents(BindingContext.ANNOTATION).values.forEach {
      it.fqName?.let(classUsageRecorder::recordClass)
    }

    bindingTrace.bindingContext.getSliceContents(BindingContext.TYPE).values.forEach {
      classUsageRecorder.recordClass(it)
    }

    bindingTrace.bindingContext.getSliceContents(BindingContext.QUALIFIER).values.forEach {
      classUsageRecorder.recordClass(it.descriptor)
    }

    bindingTrace.bindingContext.getSliceContents(BindingContext.CLASS).values.forEach {
      classUsageRecorder.recordClass(it)
    }

    bindingTrace.bindingContext.getSliceContents(BindingContext.REFERENCE_TARGET).values.forEach {
      classUsageRecorder.recordClass(it)
    }

    bindingTrace.bindingContext.getSliceContents(BindingContext.EXPECTED_EXPRESSION_TYPE)
        .values
        .forEach { classUsageRecorder.recordClass(it) }

    ObjectMapper().writeValue(File(outputPath), classUsageRecorder.getUsage())
    return null
  }
}
