package com.android.tools.r8.graph;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.utils.InternalOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class SmaliWriter extends DexByteCodeWriter {

  public SmaliWriter(DexApplication application,
      InternalOptions options) {
    super(application, options);
  }

  /** Return smali source for the application code. */
  public static String smali(DexApplication application, InternalOptions options) {
    SmaliWriter writer = new SmaliWriter(application, options);
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(os)) {
      writer.write(ps);
    } catch (IOException e) {
      throw new CompilationError("Failed to generate smali sting", e);
    }
    return new String(os.toByteArray(), StandardCharsets.UTF_8);
  }

  @Override
  String getFileEnding() {
    return ".smali";
  }

  @Override
  void writeClassHeader(DexProgramClass clazz, PrintStream ps) {
    ps.append(".class ");
    ps.append(clazz.accessFlags.toSmaliString());
    ps.append(" ");
    ps.append(clazz.type.toSmaliString());
    ps.append("\n\n");
    if (clazz.type != application.dexItemFactory.objectType) {
      ps.append(".super ");
      ps.append(clazz.superType.toSmaliString());
      ps.append("\n");
      for (DexType iface : clazz.interfaces.values) {
        ps.append(".implements ");
        ps.append(iface.toSmaliString());
        ps.append("\n");
      }
    }
  }

  @Override
  void writeClassFooter(DexProgramClass clazz, PrintStream ps) {
    ps.append("# End of class ");
    ps.append(clazz.type.toSmaliString());
    ps.append("\n");
  }

  @Override
  void writeMethod(DexEncodedMethod method, PrintStream ps) {
    ps.append("\n");
    ps.append(method.toSmaliString(application.getProguardMap()));
    ps.append("\n");
  }

  @Override
  void writeField(DexEncodedField field, PrintStream ps) {
    // Not yet implemented.
  }
}
