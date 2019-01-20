import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class Compiler implements JavaCompiler {
  private final JavaCompiler inner = ToolProvider.getSystemJavaCompiler();

  @Override
  public CompilationTask getTask(
      Writer out,
      JavaFileManager fileManager,
      DiagnosticListener<? super JavaFileObject> diagnosticListener,
      Iterable<String> options,
      Iterable<String> classes,
      Iterable<? extends JavaFileObject> compilationUnits) {
    return inner.getTask(out, fileManager, diagnosticListener, options, classes, compilationUnits);
  }

  @Override
  public StandardJavaFileManager getStandardFileManager(
      DiagnosticListener<? super JavaFileObject> diagnosticListener,
      Locale locale,
      Charset charset) {
    return inner.getStandardFileManager(diagnosticListener, locale, charset);
  }

  @Override
  public int isSupportedOption(String option) {
    return inner.isSupportedOption(option);
  }

  @Override
  public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
    return inner.run(in, out, err, arguments);
  }

  @Override
  public Set<SourceVersion> getSourceVersions() {
    return inner.getSourceVersions();
  }
}
