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
    if (!generated) {
      try {
        JavaFileObject sourceFile =
            processingEnv.getFiler().createSourceFile("com.facebook.buck.Generated");
        try (OutputStream outputStream = sourceFile.openOutputStream()) {
          outputStream.write(
              "package com.facebook.buck;\npublic class Generated { public static final int Foo = 3; }"
                  .getBytes());
        }
        generated = true;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    return true;
  }
}
