package com.example.ap.kotlinapgenjava

import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement

import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec

class KotlinAnnotationProcessor : AbstractProcessor() {

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(KotlinAnnotation::class.java.name)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latest()
    }

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        roundEnv.getElementsAnnotatedWith(KotlinAnnotation::class.java)
                .forEach {
                    val className = it.simpleName.toString()
                    val pkg = processingEnv.elementUtils.getPackageOf(it).toString()
                    generateClass(className, pkg)
                }
        return true
    }

    private fun generateClass(name: String, pkg: String) {
        val fileName = name + "_"
        val helloWorld = TypeSpec.classBuilder(fileName).addModifiers(Modifier.PUBLIC, Modifier.FINAL).build()
        val javaFile = JavaFile.builder(pkg, helloWorld).build()
        javaFile.writeTo(processingEnv.getFiler())
    }
}
