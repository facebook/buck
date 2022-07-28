package com.example.ap.kotlinap_ksp_dump_file_usage

import java.io.File
import java.io.OutputStreamWriter
import java.net.URI
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import com.example.ap.kotlinannotation.KspKotlinAnnotation
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.symbol.impl.binary.KSClassDeclarationDescriptorImpl
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import org.jetbrains.kotlin.load.kotlin.getSourceElement
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement

/**
 * A dummy Ksp Processor that support [fileAccessHistoryReportFile] param.
 */
class AnnotationProcessorKotlinKsp(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>) : SymbolProcessor {

  private val fileAccessHistory = mutableSetOf<URI>()
  private val fileAccessReportFile = options[OPTION_FILE_ACCESS_HISTORY_REPORT_FILE]

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val visitor: KSVisitorVoid =
      object : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
          val pkgName = classDeclaration.packageName.asString()
          val name = classDeclaration.simpleName.asString()
          generateClass(name, pkgName)
        }
      }

    val kspKotlinAnnotationName = KspKotlinAnnotation::class.qualifiedName!!
    resolver.getSymbolsWithAnnotation(kspKotlinAnnotationName)
      .forEach { it.accept(visitor, Unit) }

    recordFileAccessByClassName(resolver, kspKotlinAnnotationName)

    return emptyList()
  }

  private fun generateClass(name: String, pkg: String) {
    val fileName = "${name}_kspgen"

    val fileSpec = FileSpec
      .builder(pkg, fileName)
      .addType(TypeSpec.classBuilder(fileName).build())
      .build()

    val fileOutputStream = codeGenerator.createNewFile(Dependencies.ALL_FILES, pkg, fileName)
    val writer = OutputStreamWriter(fileOutputStream)
    writer.use(fileSpec::writeTo)
  }

  override fun finish() {
    dumpFileAccessHistory()
  }

  /**
   * Querying file for ksp class declaration is more complicated.
   * For simplicity, we only included a minimal snippet of code that works for
   * [KspKotlinAnnotation.class]
   */
  private fun recordFileAccessByClassName(resolver: Resolver, name: String) {
    val declaration = resolver.getClassDeclarationByName(name)
    (declaration as? KSClassDeclarationDescriptorImpl)?.descriptor?.let { descriptor ->
      val source = getSourceElement(descriptor)
      if (source is KotlinJvmBinarySourceElement) {
        fileAccessHistory.add(URI.create("jar:${File(source.binaryClass.location).toURI()}"))
      }
    }
  }

  private fun dumpFileAccessHistory() {
    fileAccessReportFile?.let {
      File(it).appendText("${fileAccessHistory.sorted().joinToString("\n")}\n")
    }
  }

  companion object {
    private const val OPTION_FILE_ACCESS_HISTORY_REPORT_FILE = "fileAccessHistoryReportFile"
  }
}


class AnnotationProcessorKotlinKspProvider : SymbolProcessorProvider {
  override fun create(env: SymbolProcessorEnvironment): SymbolProcessor {
    return AnnotationProcessorKotlinKsp(env.codeGenerator, env.logger, env.options)
  }
}
