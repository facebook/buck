package com.example.ap.kotlinap_ksp

import java.io.File
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
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
import java.io.OutputStreamWriter

import com.example.ap.kotlinannotation.KspKotlinAnnotation
import com.example.ap.javaannotation.JavaAnnotation
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec

class AnnotationProcessorKotlinKsp(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>) : SymbolProcessor {

  override fun process(resolver: Resolver): List<KSAnnotated> {

    val visitor: KSVisitorVoid =
      object : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
          val pkgName = classDeclaration.packageName.asString()
          val name = classDeclaration.simpleName.asString()
          generateClass(name, pkgName)
        }
      }

    resolver.getSymbolsWithAnnotation(KspKotlinAnnotation::class.qualifiedName!!)
      .forEach {
          it.accept(visitor, Unit)
      }

    resolver.getSymbolsWithAnnotation(JavaAnnotation::class.qualifiedName!!)
      .forEach {
          it.accept(visitor, Unit)
      }

    return emptyList()
  }

  private fun generateClass(name: String, pkg: String) {
    val fileName = "${name}_kspgen"

    val fileSpec = FileSpec
      .builder(pkg, fileName)
      .addType(TypeSpec
        .classBuilder(fileName)
        .build()
      ).build()

    val fileOutputStream = codeGenerator.createNewFile(Dependencies.ALL_FILES, pkg, fileName)
    val writer = OutputStreamWriter(fileOutputStream)
    writer.use(fileSpec::writeTo)
  }
}


class AnnotationProcessorKotlinKspProvider : SymbolProcessorProvider {
  override fun create(env: SymbolProcessorEnvironment): SymbolProcessor {
    return AnnotationProcessorKotlinKsp(env.codeGenerator, env.logger, env.options)
  }
}
