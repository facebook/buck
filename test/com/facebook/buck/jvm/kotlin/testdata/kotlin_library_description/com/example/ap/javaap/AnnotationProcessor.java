package com.example.ap.javaap;

import com.example.ap.javaannotation.JavaAnnotation;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

@SupportedAnnotationTypes("com.example.ap.javaannotation.JavaAnnotation")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedOptions({})
public class AnnotationProcessor extends AbstractProcessor {

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(JavaAnnotation.class);
    for (Element e : elements) {
      try {
        String className = e.getSimpleName().toString();
        String pkg = processingEnv.getElementUtils().getPackageOf(e).toString();
        generateClass(className, pkg);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
    return true;
  }

  public void generateClass(String name, String pkg) throws IOException {
    String fileName = name + "_";

    TypeSpec helloWorld =
        TypeSpec.classBuilder(fileName).addModifiers(Modifier.PUBLIC, Modifier.FINAL).build();

    JavaFile javaFile = JavaFile.builder(pkg, helloWorld).build();

    javaFile.writeTo(processingEnv.getFiler());
  }
}
