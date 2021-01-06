// Copyright 2004-present Facebook. All Rights Reserved.

package com.example;

import com.example.gwt.shared.MyFieldAnnotation;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;

@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class AnnotationProcessor extends AbstractProcessor {

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return new HashSet<String>(Arrays.asList(MyFieldAnnotation.class.getName()));
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (annotations.size() == 0) return true;
    try {
      JavaFileObject sourceFile =
          processingEnv.getFiler().createSourceFile("com.example.gwt.shared.MyFactory");
      try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
        out.println("package com.example.gwt.shared;");
        out.println("public class MyFactory {}");
      }
    } catch (IOException ex) {
      throw new RuntimeException("Unexpected IOException caught", ex);
    }

    return false;
  }
}
