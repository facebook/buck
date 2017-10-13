package com.example.ap;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;

@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedOptions({})
public class AnnotationProcessor extends AbstractProcessor {
  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (roundEnv.processingOver()) {
      Filer filer = processingEnv.getFiler();
      try {
        JavaFileObject sourceFile = filer.createSourceFile("com.example.ap.Test");
        try (OutputStream out = sourceFile.openOutputStream()) {
          out.write("package com.example.ap; class Test { }".getBytes());
        }
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    return false;
  }
}
