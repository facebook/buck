package com.example.ap.kotlinap

import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement

import com.example.ap.kotlinannotation.KotlinAnnotation

class KotlinAnnotationProcessorWithApoption : AbstractProcessor() {

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(KotlinAnnotation::class.java.name)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latest()
    }

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if (!processingEnv.getOptions().get("someApoption").equals("someApoptionValue")) {
            throw java.lang.IllegalStateException("KotlinAnnotationProcessorWithApoption could not retrieve the apoption")
        }

        return true
    }
}
