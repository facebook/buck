package com.example.buck;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;

@SupportedAnnotationTypes("*")
public class AnnotationProcessor extends AbstractProcessor {
  private boolean generated = false;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    try {
      crash();
      return true;
    } catch (RuntimeException e) {
      throw new RuntimeException(e);
    }
  }

  private void crash() {
    throw new IllegalArgumentException("Test crash!");
  }
}
