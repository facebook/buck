package com.example.ap.throwingkotlinap

import com.example.ap.kotlinannotation.ThrowingKotlinAnnotation
import com.google.auto.service.AutoService
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement

@AutoService(Processor::class)
class ThrowingAnnotationProcessorKotlin : AbstractProcessor() {

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(ThrowingKotlinAnnotation::class.java.name)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latest()
    }

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        throw java.lang.RuntimeException(
            "If we get to here then this annotation processor was incorrectly loaded!")
    }
}
