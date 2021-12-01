package com.example.ap.kotlinap_ksp_multiround

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

import com.example.ap.kotlinannotation.KspAnnotationRound1
import com.example.ap.kotlinannotation.KspAnnotationRound2
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec

class AnnotationProcessorKspMultiround(private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger, private val options: Map<String, String>) : SymbolProcessor {

  override fun process(resolver: Resolver): List<KSAnnotated> {

    val visitorRound1: KSVisitorVoid = object : KSVisitorVoid() {
      override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val pkgName = classDeclaration.packageName.asString()
        val name = classDeclaration.simpleName.asString()
        generateClassRound1(name, pkgName)
      }
    }

    val visitorRound2: KSVisitorVoid = object : KSVisitorVoid() {
      override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val pkgName = classDeclaration.packageName.asString()
        val name = classDeclaration.simpleName.asString()
        generateClassRound2(name, pkgName)
      }
    }

    resolver.getSymbolsWithAnnotation(KspAnnotationRound1::class.qualifiedName!!).forEach {
        it.accept(visitorRound1, Unit)
      }

    resolver.getSymbolsWithAnnotation(KspAnnotationRound2::class.qualifiedName!!).forEach {
        it.accept(visitorRound2, Unit)
      }

    return emptyList()
  }

  private fun generateClassRound1(name: String, pkg: String) {
    val fileName = "${name}_A"

    val fileSpec = FileSpec.builder(pkg, fileName)
      .addType(TypeSpec.classBuilder(fileName).addAnnotation(KspAnnotationRound2::class).build())
      .build()

    val fileOutputStream = codeGenerator.createNewFile(Dependencies.ALL_FILES, pkg, fileName)
    val writer = OutputStreamWriter(fileOutputStream)
    writer.use(fileSpec::writeTo)
  }

  private fun generateClassRound2(name: String, pkg: String) {
    val fileName = "${name}_B"

    val fileSpec =
      FileSpec.builder(pkg, fileName).addType(TypeSpec.classBuilder(fileName).build()).build()

    val fileOutputStream = codeGenerator.createNewFile(Dependencies.ALL_FILES, pkg, fileName)
    val writer = OutputStreamWriter(fileOutputStream)
    writer.use(fileSpec::writeTo)
  }
}

class AnnotationProcessorKspMultiroundProvider : SymbolProcessorProvider {
  override fun create(env: SymbolProcessorEnvironment): SymbolProcessor {
    return AnnotationProcessorKspMultiround(env.codeGenerator, env.logger, env.options)
  }
}
