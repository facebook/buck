import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes("*")
public class AnnotationProcessor extends AbstractProcessor {
  private boolean generated = false;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (!roundEnv.processingOver() && !generated) {
      try {
        Filer filer = processingEnv.getFiler();

        JavaFileObject cSource = filer.createSourceFile("ImmutableC");
        try (OutputStream outputStream = cSource.openOutputStream()) {
          outputStream.write("public class ImmutableC { }".getBytes());
        }

        JavaFileObject dSource = filer.createSourceFile("RemovableD");
        try (OutputStream outputStream = dSource.openOutputStream()) {
          outputStream.write("public class RemovableD { }".getBytes());
        }

        FileObject dResource =
            filer.createResource(StandardLocation.CLASS_OUTPUT, "", "RemovableD");
        try (OutputStream outputStream = dResource.openOutputStream()) {
          outputStream.write("foo".getBytes());
        }

        generated = true;
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    return true;
  }
}
