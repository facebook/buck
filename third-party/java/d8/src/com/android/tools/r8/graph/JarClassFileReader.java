// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import static org.objectweb.asm.ClassReader.SKIP_FRAMES;
import static org.objectweb.asm.Opcodes.ACC_DEPRECATED;
import static org.objectweb.asm.Opcodes.ASM6;

import com.android.tools.r8.Resource.Origin;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexValue.DexValueAnnotation;
import com.android.tools.r8.graph.DexValue.DexValueArray;
import com.android.tools.r8.graph.DexValue.DexValueBoolean;
import com.android.tools.r8.graph.DexValue.DexValueByte;
import com.android.tools.r8.graph.DexValue.DexValueChar;
import com.android.tools.r8.graph.DexValue.DexValueDouble;
import com.android.tools.r8.graph.DexValue.DexValueEnum;
import com.android.tools.r8.graph.DexValue.DexValueFloat;
import com.android.tools.r8.graph.DexValue.DexValueInt;
import com.android.tools.r8.graph.DexValue.DexValueLong;
import com.android.tools.r8.graph.DexValue.DexValueNull;
import com.android.tools.r8.graph.DexValue.DexValueShort;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.graph.DexValue.DexValueType;
import com.android.tools.r8.graph.JarCode.ReparseContext;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ProgramResource.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

/**
 * Java/Jar class reader for constructing dex/graph structure.
 */
public class JarClassFileReader {

  // Hidden ASM "synthetic attribute" bit we need to clear.
  private static int ACC_SYNTHETIC_ATTRIBUTE = 0x40000;

  private final JarApplicationReader application;
  private final Consumer<DexClass> classConsumer;

  public JarClassFileReader(
      JarApplicationReader application, Consumer<DexClass> classConsumer) {
    this.application = application;
    this.classConsumer = classConsumer;
  }

  public void read(Origin origin, ClassKind classKind, InputStream input) throws IOException {
    ClassReader reader = new ClassReader(input);
    reader.accept(new CreateDexClassVisitor(
        origin, classKind, reader.b, application, classConsumer), SKIP_FRAMES);
  }

  private static int cleanAccessFlags(int access) {
    // Clear the "synthetic attribute" and "deprecated" attribute-flags if present.
    return access & ~ACC_SYNTHETIC_ATTRIBUTE & ~ACC_DEPRECATED;
  }

  private static AnnotationVisitor createAnnotationVisitor(String desc, boolean visible,
      List<DexAnnotation> annotations,
      JarApplicationReader application) {
    assert annotations != null;
    int visiblity = visible ? DexAnnotation.VISIBILITY_RUNTIME : DexAnnotation.VISIBILITY_BUILD;
    return new CreateAnnotationVisitor(application, (names, values) ->
        annotations.add(new DexAnnotation(visiblity,
            createEncodedAnnotation(desc, names, values, application))));
  }

  private static DexEncodedAnnotation createEncodedAnnotation(String desc,
      List<DexString> names, List<DexValue> values, JarApplicationReader application) {
    assert (names == null && values.isEmpty())
        || (names != null && !names.isEmpty() && names.size() == values.size());
    DexAnnotationElement[] elements = new DexAnnotationElement[values.size()];
    for (int i = 0; i < values.size(); i++) {
      elements[i] = new DexAnnotationElement(names.get(i), values.get(i));
    }
    return new DexEncodedAnnotation(application.getTypeFromDescriptor(desc), elements);
  }

  private static class CreateDexClassVisitor extends ClassVisitor {
    private final Origin origin;
    private final ClassKind classKind;
    private final JarApplicationReader application;
    private final Consumer<DexClass> classConsumer;
    private final ReparseContext context = new ReparseContext();

    // DexClass data.
    private int version;
    private DexType type;
    private ClassAccessFlags accessFlags;
    private DexType superType;
    private DexTypeList interfaces;
    private DexString sourceFile;
    private List<DexType> memberClasses = null;
    private List<DexAnnotation> annotations = null;
    private List<DexAnnotationElement> defaultAnnotations = null;
    private DexAnnotation innerClassAnnotation = null;
    private DexAnnotation enclosingAnnotation = null;
    private final List<DexEncodedField> staticFields = new ArrayList<>();
    private final List<DexEncodedField> instanceFields = new ArrayList<>();
    private final List<DexEncodedMethod> directMethods = new ArrayList<>();
    private final List<DexEncodedMethod> virtualMethods = new ArrayList<>();

    public CreateDexClassVisitor(
        Origin origin,
        ClassKind classKind,
        byte[] classCache,
        JarApplicationReader application,
        Consumer<DexClass> classConsumer) {
      super(ASM6);
      this.origin = origin;
      this.classKind = classKind;
      this.classConsumer = classConsumer;
      this.context.classCache = classCache;
      this.application = application;
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      if (type == application.getTypeFromName(name)) {
        // If the inner class is this class, record its original access flags and name in its
        // InnerClass annotation. We defer storing the actual annotation until we have found
        // a matching enclosing annotation.
        assert innerClassAnnotation == null;
        innerClassAnnotation = DexAnnotation.createInnerClassAnnotation(
            innerName, access, application.getFactory());
        // If this is a named inner class (in which case outerName and innerName are defined)
        // record the outer class in its EnclosingClass annotation.
        if (outerName != null && innerName != null) {
          assert enclosingAnnotation == null;
          enclosingAnnotation = DexAnnotation.createEnclosingClassAnnotation(
              application.getTypeFromName(outerName),
              application.getFactory());
        }
      } else if (outerName != null && innerName != null
          && type == application.getTypeFromName(outerName)) {
        // If the inner class is a member of this class, record it for the MemberClasses annotation.
        if (memberClasses == null) {
          memberClasses = new ArrayList<>();
        }
        memberClasses.add(application.getTypeFromName(name));
      }
    }

    @Override
    public void visitOuterClass(String owner, String name, String desc) {
      assert enclosingAnnotation == null;
      // This is called for anonymous inner classes defined in classes or in methods.
      DexType ownerType = application.getTypeFromName(owner);
      if (name == null) {
        enclosingAnnotation = DexAnnotation.createEnclosingClassAnnotation(
            ownerType, application.getFactory());
      } else {
        enclosingAnnotation = DexAnnotation.createEnclosingMethodAnnotation(
            application.getMethod(ownerType, name, desc), application.getFactory());
      }
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
        String[] interfaces) {
      this.version = version;
      accessFlags = ClassAccessFlags.fromCfAccessFlags(cleanAccessFlags(access));
      type = application.getTypeFromName(name);
      assert superName != null || name.equals(Constants.JAVA_LANG_OBJECT_NAME);
      superType = superName == null ? null : application.getTypeFromName(superName);
      this.interfaces = application.getTypeListFromNames(interfaces);
      if (signature != null && !signature.isEmpty()) {
        addAnnotation(DexAnnotation.createSignatureAnnotation(signature, application.getFactory()));
      }
    }

    @Override
    public void visitSource(String source, String debug) {
      if (source != null) {
        sourceFile = application.getString(source);
      }
      if (debug != null) {
        getAnnotations().add(
                DexAnnotation.createSourceDebugExtensionAnnotation(
                    new DexValueString(application.getString(debug)), application.getFactory()));
      }
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String desc, String signature, Object value) {
      return new CreateFieldVisitor(this, access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      return new CreateMethodVisitor(access, name, desc, signature, exceptions, this);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return createAnnotationVisitor(desc, visible, getAnnotations(), application);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public void visitAttribute(Attribute attr) {
      // Unknown attribute must only be ignored
    }

    @Override
    public void visitEnd() {
      if (memberClasses != null) {
        assert !memberClasses.isEmpty();
        addAnnotation(DexAnnotation.createMemberClassesAnnotation(
            memberClasses, application.getFactory()));
      }
      if (innerClassAnnotation != null) {
        if (enclosingAnnotation == null) {
          application.options.warningMissingEnclosingMember = true;
        } else {
          addAnnotation(innerClassAnnotation);
          addAnnotation(enclosingAnnotation);
        }
      }
      if (defaultAnnotations != null) {
        addAnnotation(DexAnnotation.createAnnotationDefaultAnnotation(
            type, defaultAnnotations, application.getFactory()));
      }
      DexClass clazz = classKind.create(
          type,
          Kind.CLASS,
          origin,
          accessFlags,
          superType,
          interfaces,
          sourceFile,
          createAnnotationSet(annotations),
          staticFields.toArray(new DexEncodedField[staticFields.size()]),
          instanceFields.toArray(new DexEncodedField[instanceFields.size()]),
          directMethods.toArray(new DexEncodedMethod[directMethods.size()]),
          virtualMethods.toArray(new DexEncodedMethod[virtualMethods.size()]));
      if (classKind == ClassKind.PROGRAM) {
        context.owner = clazz.asProgramClass();
      }
      if (clazz.isProgramClass()) {
        clazz.asProgramClass().setClassFileVersion(version);
      }
      classConsumer.accept(clazz);
    }

    private void addDefaultAnnotation(String name, DexValue value) {
      if (defaultAnnotations == null) {
        defaultAnnotations = new ArrayList<>();
      }
      defaultAnnotations.add(new DexAnnotationElement(application.getString(name), value));
    }

    private void addAnnotation(DexAnnotation annotation) {
      getAnnotations().add(annotation);
    }

    private List<DexAnnotation> getAnnotations() {
      if (annotations == null) {
        annotations = new ArrayList<>();
      }
      return annotations;
    }
  }

  private static DexAnnotationSet createAnnotationSet(List<DexAnnotation> annotations) {
    return annotations == null || annotations.isEmpty()
        ? DexAnnotationSet.empty()
        : new DexAnnotationSet(annotations.toArray(new DexAnnotation[annotations.size()]));
  }

  private static class CreateFieldVisitor extends FieldVisitor {
    private final CreateDexClassVisitor parent;
    private final int access;
    private final String name;
    private final String desc;
    private final Object value;
    private List<DexAnnotation> annotations = null;

    public CreateFieldVisitor(CreateDexClassVisitor parent,
        int access, String name, String desc, String signature, Object value) {
      super(ASM6);
      this.parent = parent;
      this.access = access;
      this.name = name;
      this.desc = desc;
      this.value = value;
      if (signature != null && !signature.isEmpty()) {
        addAnnotation(DexAnnotation.createSignatureAnnotation(
            signature, parent.application.getFactory()));
      }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return createAnnotationVisitor(desc, visible, getAnnotations(), parent.application);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public void visitEnd() {
      FieldAccessFlags flags = FieldAccessFlags.fromCfAccessFlags(cleanAccessFlags(access));
      DexField dexField = parent.application.getField(parent.type, name, desc);
      DexAnnotationSet annotationSet = createAnnotationSet(annotations);
      DexValue staticValue = flags.isStatic() ? getStaticValue(value, dexField.type) : null;
      DexEncodedField field = new DexEncodedField(dexField, flags, annotationSet, staticValue);
      if (flags.isStatic()) {
        parent.staticFields.add(field);
      } else {
        parent.instanceFields.add(field);
      }
    }

    private DexValue getStaticValue(Object value, DexType type) {
      if (value == null) {
        return DexValue.defaultForType(type, parent.application.getFactory());
      }
      DexItemFactory factory = parent.application.getFactory();
      if (type == factory.booleanType) {
        int i = (Integer) value;
        assert 0 <= i && i <= 1;
        return DexValueBoolean.create(i == 1);
      }
      if (type == factory.byteType) {
        return DexValueByte.create(((Integer) value).byteValue());
      }
      if (type == factory.shortType) {
        return DexValueShort.create(((Integer) value).shortValue());
      }
      if (type == factory.charType) {
        return DexValueChar.create((char) ((Integer) value).intValue());
      }
      if (type == factory.intType) {
        return DexValueInt.create((Integer) value);
      }
      if (type == factory.floatType) {
        return DexValueFloat.create((Float) value);
      }
      if (type == factory.longType) {
        return DexValueLong.create((Long) value);
      }
      if (type == factory.doubleType) {
        return DexValueDouble.create((Double) value);
      }
      if (type == factory.stringType) {
        return new DexValueString(factory.createString((String) value));
      }
      throw new Unreachable("Unexpected static-value type " + type);
    }

    private void addAnnotation(DexAnnotation annotation) {
      getAnnotations().add(annotation);
    }

    private List<DexAnnotation> getAnnotations() {
      if (annotations == null) {
        annotations = new ArrayList<>();
      }
      return annotations;
    }
  }

  private static class CreateMethodVisitor extends MethodVisitor {
    private final int access;
    private final String name;
    private final String desc;
    private final CreateDexClassVisitor parent;
    private final int parameterCount;
    private List<DexAnnotation> annotations = null;
    private DexValue defaultAnnotation = null;
    private int fakeParameterAnnotations = 0;
    private List<List<DexAnnotation>> parameterAnnotations = null;
    private List<DexValue> parameterNames = null;
    private List<DexValue> parameterFlags = null;

    public CreateMethodVisitor(int access, String name, String desc, String signature,
        String[] exceptions, CreateDexClassVisitor parent) {
      super(ASM6);
      this.access = access;
      this.name = name;
      this.desc = desc;
      this.parent = parent;
      parameterCount = Type.getArgumentTypes(desc).length;
      if (exceptions != null && exceptions.length > 0) {
        DexValue[] values = new DexValue[exceptions.length];
        for (int i = 0; i < exceptions.length; i++) {
          values[i] = new DexValueType(parent.application.getTypeFromName(exceptions[i]));
        }
        addAnnotation(DexAnnotation.createThrowsAnnotation(
            values, parent.application.getFactory()));
      }
      if (signature != null && !signature.isEmpty()) {
        addAnnotation(DexAnnotation.createSignatureAnnotation(
            signature, parent.application.getFactory()));
      }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return createAnnotationVisitor(desc, visible, getAnnotations(), parent.application);
    }

    @Override
    public AnnotationVisitor visitAnnotationDefault() {
      return new CreateAnnotationVisitor(parent.application, (names, elements) -> {
        assert elements.size() == 1;
        defaultAnnotation = elements.get(0);
      });
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
      // ASM decided to workaround a javac bug that incorrectly deals with synthesized parameter
      // annotations. However, that leads us to have different behavior than javac+jvm and
      // dx+art. The workaround is to use a non-existing descriptor "Ljava/lang/Synthetic;" for
      // exactly this case. In order to remove the workaround we ignore all annotations
      // with that descriptor. If javac is fixed, the ASM workaround will not be hit and we will
      // never see this non-existing annotation descriptor. ASM uses the same check to make
      // sure to undo their workaround for the javac bug in their MethodWriter.
      if (desc.equals("Ljava/lang/Synthetic;")) {
        // We can iterate through all the parameters twice. Once for visible and once for
        // invisible parameter annotations. We only record the number of fake parameter
        // annotations once.
        if (parameterAnnotations == null) {
          fakeParameterAnnotations++;
        }
        return null;
      }
      if (parameterAnnotations == null) {
        int adjustedParameterCount = parameterCount - fakeParameterAnnotations;
        parameterAnnotations = new ArrayList<>(adjustedParameterCount);
        for (int i = 0; i < adjustedParameterCount; i++) {
          parameterAnnotations.add(new ArrayList<>());
        }
      }
      assert mv == null;
      return createAnnotationVisitor(desc, visible,
          parameterAnnotations.get(parameter - fakeParameterAnnotations), parent.application);
    }

    @Override
    public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
        Label[] start, Label[] end, int[] index, String desc, boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public void visitParameter(String name, int access) {
      if (parameterNames == null) {
        assert parameterFlags == null;
        parameterNames = new ArrayList<>(parameterCount);
        parameterFlags = new ArrayList<>(parameterCount);
      }
      parameterNames.add(new DexValueString(parent.application.getFactory().createString(name)));
      parameterFlags.add(DexValueInt.create(access));
      super.visitParameter(name, access);
    }

    @Override
    public void visitEnd() {
      DexMethod method = parent.application.getMethod(parent.type, name, desc);
      MethodAccessFlags flags = createMethodAccessFlags(access);
      Code code = null;
      if (!flags.isAbstract()
          && !flags.isNative()
          && parent.classKind == ClassKind.PROGRAM) {
        code = new JarCode(method, parent.origin, parent.context, parent.application);
      }
      DexAnnotationSetRefList parameterAnnotationSets;
      if (parameterAnnotations == null) {
        parameterAnnotationSets = DexAnnotationSetRefList.empty();
      } else {
        DexAnnotationSet[] sets = new DexAnnotationSet[parameterAnnotations.size()];
        for (int i = 0; i < parameterAnnotations.size(); i++) {
          sets[i] = createAnnotationSet(parameterAnnotations.get(i));
        }
        parameterAnnotationSets = new DexAnnotationSetRefList(sets);
      }
      InternalOptions internalOptions = parent.application.options;
      if (parameterNames != null && internalOptions.allowParameterName
          && internalOptions.canUseParameterNameAnnotations()) {
        assert parameterFlags != null;
        if (parameterNames.size() != parameterCount) {
          internalOptions.warningInvalidParameterAnnotations(
              method, parent.origin, parameterCount, parameterNames.size());
        }
        getAnnotations().add(DexAnnotation.createMethodParametersAnnotation(
            parameterNames.toArray(new DexValue[parameterNames.size()]),
            parameterFlags.toArray(new DexValue[parameterFlags.size()]),
            parent.application.getFactory()));
      }
      DexEncodedMethod dexMethod = new DexEncodedMethod(method, flags,
          createAnnotationSet(annotations), parameterAnnotationSets, code);
      if (flags.isStatic() || flags.isConstructor() || flags.isPrivate()) {
        parent.directMethods.add(dexMethod);
      } else {
        parent.virtualMethods.add(dexMethod);
      }
      if (defaultAnnotation != null) {
        parent.addDefaultAnnotation(name, defaultAnnotation);
      }
    }

    private List<DexAnnotation> getAnnotations() {
      if (annotations == null) {
        annotations = new ArrayList<>();
      }
      return annotations;
    }

    private void addAnnotation(DexAnnotation annotation) {
      getAnnotations().add(annotation);
    }

    private MethodAccessFlags createMethodAccessFlags(int access) {
      boolean isConstructor =
          name.equals(Constants.INSTANCE_INITIALIZER_NAME)
              || name.equals(Constants.CLASS_INITIALIZER_NAME);
      return MethodAccessFlags.fromCfAccessFlags(cleanAccessFlags(access), isConstructor);
    }
  }

  private static class CreateAnnotationVisitor extends AnnotationVisitor {
    private final JarApplicationReader application;
    private final BiConsumer<List<DexString>, List<DexValue>> onVisitEnd;
    private List<DexString> names = null;
    private final List<DexValue> values = new ArrayList<>();

    public CreateAnnotationVisitor(
        JarApplicationReader application, BiConsumer<List<DexString>, List<DexValue>> onVisitEnd) {
      super(ASM6);
      this.application = application;
      this.onVisitEnd = onVisitEnd;
    }

    @Override
    public void visit(String name, Object value) {
      addElement(name, getDexValue(value));
    }

    @Override
    public void visitEnum(String name, String desc, String value) {
      DexType owner = application.getTypeFromDescriptor(desc);
      addElement(name, new DexValueEnum(application.getField(owner, value, desc)));
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String desc) {
      return new CreateAnnotationVisitor(application, (names, values) ->
          addElement(name, new DexValueAnnotation(
              createEncodedAnnotation(desc, names, values, application))));
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      return new CreateAnnotationVisitor(application, (names, values) -> {
        assert names == null;
        addElement(name, new DexValueArray(values.toArray(new DexValue[values.size()])));
      });
    }

    @Override
    public void visitEnd() {
      onVisitEnd.accept(names, values);
    }

    private void addElement(String name, DexValue value) {
      if (name != null) {
        if (names == null){
          names = new ArrayList<>();
        }
        names.add(application.getString(name));
      }
      values.add(value);
    }

    private static DexValueArray getDexValueArray(Object value) {
      if (value instanceof byte[]) {
        byte[] values = (byte[]) value;
        DexValue[] elements = new DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueByte.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof boolean[]) {
        boolean[] values = (boolean[]) value;
        DexValue[] elements = new DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueBoolean.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof char[]) {
        char[] values = (char[]) value;
        DexValue[] elements = new DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueChar.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof short[]) {
        short[] values = (short[]) value;
        DexValue[] elements = new DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueShort.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof int[]) {
        int[] values = (int[]) value;
        DexValue[] elements = new DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueInt.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof long[]) {
        long[] values = (long[]) value;
        DexValue[] elements = new DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueLong.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof float[]) {
        float[] values = (float[]) value;
        DexValue[] elements = new DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueFloat.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof double[]) {
        double[] values = (double[]) value;
        DexValue[] elements = new DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueDouble.create(values[i]);
        }
        return new DexValueArray(elements);
      }
      throw new Unreachable("Unexpected type of annotation value: " + value);
    }

    private DexValue getDexValue(Object value) {
      if (value == null) {
        return DexValueNull.NULL;
      }
      if (value instanceof Byte) {
        return DexValueByte.create((Byte) value);
      } else if (value instanceof Boolean) {
        return DexValueBoolean.create((Boolean) value);
      } else if (value instanceof Character) {
        return DexValueChar.create((Character) value);
      } else if (value instanceof Short) {
        return DexValueShort.create((Short) value);
      } else if (value instanceof Integer) {
        return DexValueInt.create((Integer) value);
      } else if (value instanceof Long) {
        return DexValueLong.create((Long) value);
      } else if (value instanceof Float) {
        return DexValueFloat.create((Float) value);
      } else if (value instanceof Double) {
        return DexValueDouble.create((Double) value);
      } else if (value instanceof String) {
        return new DexValueString(application.getString((String) value));
      } else if (value instanceof Type) {
        return new DexValueType(application.getTypeFromDescriptor(((Type) value).getDescriptor()));
      }
      return getDexValueArray(value);
    }
  }
}
