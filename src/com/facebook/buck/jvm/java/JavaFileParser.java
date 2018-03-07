/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.log.Logger;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;

/**
 * Extracts the set of exported symbols (class and enum names) from a Java code file, using the
 * ASTParser from Eclipse.
 */
public class JavaFileParser {

  private static final Logger LOG = Logger.get(JavaFileParser.class);

  private final int jlsLevel;
  private final String javaVersion;

  private static final ImmutableMap<String, String> javaVersionMap =
      ImmutableMap.<String, String>builder()
          .put("1", JavaCore.VERSION_1_1)
          .put("2", JavaCore.VERSION_1_2)
          .put("3", JavaCore.VERSION_1_3)
          .put("4", JavaCore.VERSION_1_4)
          .put("5", JavaCore.VERSION_1_5)
          .put("6", JavaCore.VERSION_1_6)
          .put("7", JavaCore.VERSION_1_7)
          .put("8", JavaCore.VERSION_1_8)
          .build();

  /**
   * Types that are in java.lang. These can be used without being imported.
   *
   * <p>Current as of Java 8:
   * https://docs.oracle.com/javase/8/docs/api/java/lang/package-summary.html
   */
  private static final Set<String> JAVA_LANG_TYPES =
      ImmutableSet.of(
          // Interface
          "Appendable",
          "AutoCloseable",
          "CharSequence",
          "Cloneable",
          "Comparable",
          "Iterable",
          "Readable",
          "Runnable",
          "Thread.UncaughtExceptionHandler",

          // Class
          "Boolean",
          "Byte",
          "Character",
          "Character.Subset",
          "Class",
          "ClassLoader",
          "ClassValue",
          "Compiler",
          "Double",
          "Enum",
          "Float",
          "InheritableThreadLocal",
          "Integer",
          "Long",
          "Math",
          "Number",
          "Object",
          "Package",
          "Process",
          "ProcessBuilder",
          "ProcessBuilder.Redirect",
          "Runtime",
          "RuntimePermission",
          "SecurityManager",
          "Short",
          "StackTraceElement",
          "StrictMath",
          "String",
          "StringBuffer",
          "StringBuilder",
          "System",
          "Thread",
          "ThreadGroup",
          "ThreadLocal",
          "Throwable",
          "Void",

          // Enum
          "Character.UnicodeScript",
          "ProcessBuilder.Redirect.Type",
          "Thread.State",

          // Exception
          "ArithmeticException",
          "ArrayIndexOutOfBoundsException",
          "ArrayStoreException",
          "ClassCastException",
          "ClassNotFoundException",
          "CloneNotSupportedException",
          "EnumConstantNotPresentException",
          "Exception",
          "IllegalAccessException",
          "IllegalArgumentException",
          "IllegalMonitorStateException",
          "IllegalStateException",
          "IllegalThreadStateException",
          "IndexOutOfBoundsException",
          "InstantiationException",
          "InterruptedException",
          "NegativeArraySizeException",
          "NoSuchFieldException",
          "NoSuchMethodException",
          "NullPointerException",
          "NumberFormatException",
          "ReflectiveOperationException",
          "RuntimeException",
          "StringIndexOutOfBoundsException",
          "TypeNotPresentException",
          "UnsupportedOperationException",

          // Error
          "AbstractMethodError",
          "AssertionError",
          "BootstrapMethodError",
          "ClassCircularityError",
          "ClassFormatError",
          "Error",
          "ExceptionInInitializerError",
          "IllegalAccessError",
          "IncompatibleClassChangeError",
          "InstantiationError",
          "InternalError",
          "LinkageError",
          "NoClassDefFoundError",
          "NoSuchFieldError",
          "NoSuchMethodError",
          "OutOfMemoryError",
          "StackOverflowError",
          "ThreadDeath",
          "UnknownError",
          "UnsatisfiedLinkError",
          "UnsupportedClassVersionError",
          "VerifyError",
          "VirtualMachineError",

          // Annotation Types
          "Deprecated",
          "FunctionalInheritance",
          "Override",
          "SafeVarargs",
          "SuppressWarnings");

  /**
   * Symbols provided by {@code import java.io.*}.
   *
   * <p>This collection was created by running the following on an OS X laptop with Java 8
   * installed:
   *
   * <pre>
   * jar tf /Library/Java/JavaVirtualMachines/jdk1.8.0_74.jdk/Contents/Home/src.zip \
   *     | grep -E 'java/io/[^/]+$' \
   *     | sed -e 's#java/io/\(.*\)\.java#"\1",#' \
   *     | sort
   * </pre>
   */
  private static final ImmutableSet<String> JAVA_IO_TYPES =
      ImmutableSet.of(
          "Bits",
          "BufferedInputStream",
          "BufferedOutputStream",
          "BufferedReader",
          "BufferedWriter",
          "ByteArrayInputStream",
          "ByteArrayOutputStream",
          "CharArrayReader",
          "CharArrayWriter",
          "CharConversionException",
          "Closeable",
          "Console",
          "DataInput",
          "DataInputStream",
          "DataOutput",
          "DataOutputStream",
          "DefaultFileSystem",
          "DeleteOnExitHook",
          "EOFException",
          "ExpiringCache",
          "Externalizable",
          "File",
          "FileDescriptor",
          "FileFilter",
          "FileInputStream",
          "FileNotFoundException",
          "FileOutputStream",
          "FilePermission",
          "FileReader",
          "FileSystem",
          "FileWriter",
          "FilenameFilter",
          "FilterInputStream",
          "FilterOutputStream",
          "FilterReader",
          "FilterWriter",
          "Flushable",
          "IOError",
          "IOException",
          "InputStream",
          "InputStreamReader",
          "InterruptedIOException",
          "InvalidClassException",
          "InvalidObjectException",
          "LineNumberInputStream",
          "LineNumberReader",
          "NotActiveException",
          "NotSerializableException",
          "ObjectInput",
          "ObjectInputStream",
          "ObjectInputValidation",
          "ObjectOutput",
          "ObjectOutputStream",
          "ObjectStreamClass",
          "ObjectStreamConstants",
          "ObjectStreamException",
          "ObjectStreamField",
          "OptionalDataException",
          "OutputStream",
          "OutputStreamWriter",
          "PipedInputStream",
          "PipedOutputStream",
          "PipedReader",
          "PipedWriter",
          "PrintStream",
          "PrintWriter",
          "PushbackInputStream",
          "PushbackReader",
          "RandomAccessFile",
          "Reader",
          "SequenceInputStream",
          "SerialCallbackContext",
          "Serializable",
          "SerializablePermission",
          "StreamCorruptedException",
          "StreamTokenizer",
          "StringBufferInputStream",
          "StringReader",
          "StringWriter",
          "SyncFailedException",
          "UTFDataFormatException",
          "UncheckedIOException",
          "UnixFileSystem",
          "UnsupportedEncodingException",
          "WriteAbortedException",
          "Writer");

  /**
   * Symbols provided by {@code import java.util.*}.
   *
   * <p>This collection was created by running the following on an OS X laptop with Java 8
   * installed:
   *
   * <pre>
   * jar tf /Library/Java/JavaVirtualMachines/jdk1.8.0_74.jdk/Contents/Home/src.zip \
   *     | grep -E 'java/util/[^/]+$' \
   *     | sed -e 's#java/util/\(.*\)\.java#"\1",#' \
   *     | sort
   * </pre>
   */
  private static final ImmutableSet<String> JAVA_UTIL_TYPES =
      ImmutableSet.of(
          "AbstractCollection",
          "AbstractList",
          "AbstractMap",
          "AbstractQueue",
          "AbstractSequentialList",
          "AbstractSet",
          "ArrayDeque",
          "ArrayList",
          "ArrayPrefixHelpers",
          "Arrays",
          "ArraysParallelSortHelpers",
          "Base64",
          "BitSet",
          "Calendar",
          "Collection",
          "Collections",
          "ComparableTimSort",
          "Comparator",
          "Comparators",
          "ConcurrentModificationException",
          "Currency",
          "Date",
          "Deque",
          "Dictionary",
          "DoubleSummaryStatistics",
          "DualPivotQuicksort",
          "DuplicateFormatFlagsException",
          "EmptyStackException",
          "EnumMap",
          "EnumSet",
          "Enumeration",
          "EventListener",
          "EventListenerProxy",
          "EventObject",
          "FormatFlagsConversionMismatchException",
          "Formattable",
          "FormattableFlags",
          "Formatter",
          "FormatterClosedException",
          "GregorianCalendar",
          "HashMap",
          "HashSet",
          "Hashtable",
          "IdentityHashMap",
          "IllegalFormatCodePointException",
          "IllegalFormatConversionException",
          "IllegalFormatException",
          "IllegalFormatFlagsException",
          "IllegalFormatPrecisionException",
          "IllegalFormatWidthException",
          "IllformedLocaleException",
          "InputMismatchException",
          "IntSummaryStatistics",
          "InvalidPropertiesFormatException",
          "Iterator",
          "JapaneseImperialCalendar",
          "JumboEnumSet",
          "LinkedHashMap",
          "LinkedHashSet",
          "LinkedList",
          "List",
          "ListIterator",
          "ListResourceBundle",
          "Locale",
          "LocaleISOData",
          "LongSummaryStatistics",
          "Map",
          "MissingFormatArgumentException",
          "MissingFormatWidthException",
          "MissingResourceException",
          "NavigableMap",
          "NavigableSet",
          "NoSuchElementException",
          "Objects",
          "Observable",
          "Observer",
          "Optional",
          "OptionalDouble",
          "OptionalInt",
          "OptionalLong",
          "PrimitiveIterator",
          "PriorityQueue",
          "Properties",
          "PropertyPermission",
          "PropertyResourceBundle",
          "Queue",
          "Random",
          "RandomAccess",
          "RegularEnumSet",
          "ResourceBundle",
          "Scanner",
          "ServiceConfigurationError",
          "ServiceLoader",
          "Set",
          "SimpleTimeZone",
          "SortedMap",
          "SortedSet",
          "Spliterator",
          "Spliterators",
          "SplittableRandom",
          "Stack",
          "StringJoiner",
          "StringTokenizer",
          "TimSort",
          "TimeZone",
          "Timer",
          "TimerTask",
          "TooManyListenersException",
          "TreeMap",
          "TreeSet",
          "Tripwire",
          "UUID",
          "UnknownFormatConversionException",
          "UnknownFormatFlagsException",
          "Vector",
          "WeakHashMap");

  private static final ImmutableMap<String, ImmutableSet<String>> SUPPORTED_WILDCARD_IMPORTS =
      ImmutableMap.of(
          "java.util", JAVA_UTIL_TYPES,
          "java.io", JAVA_IO_TYPES);

  private JavaFileParser(int jlsLevel, String javaVersion) {
    this.jlsLevel = jlsLevel;
    this.javaVersion = javaVersion;
  }

  public static JavaFileParser createJavaFileParser(JavacOptions options) {
    String javaVersion = Preconditions.checkNotNull(javaVersionMap.get(options.getSourceLevel()));
    return new JavaFileParser(AST.JLS8, javaVersion);
  }

  public ImmutableSortedSet<String> getExportedSymbolsFromString(String code) {
    return extractFeaturesFromJavaCode(code).providedSymbols;
  }

  public Optional<String> getPackageNameFromSource(String code) {
    CompilationUnit compilationUnit = makeCompilationUnitFromSource(code);

    // A Java file might not have a package. Hopefully all of ours do though...
    PackageDeclaration packageDecl = compilationUnit.getPackage();
    if (packageDecl != null) {
      return Optional.of(packageDecl.getName().toString());
    }
    return Optional.empty();
  }

  private enum DependencyType {
    REQUIRED,
    EXPORTED,
  }

  public JavaFileFeatures extractFeaturesFromJavaCode(String code) {
    // For now, we will harcode this. Ultimately, we probably want to make this configurable via
    // .buckconfig. For example, the Buck project itself is diligent about disallowing wildcard
    // imports, but the one exception is the Java code generated via Thrift in src-gen.
    boolean shouldThrowForUnsupportedWildcardImport = false;

    AtomicBoolean isPoisonedByUnsupportedWildcardImport = new AtomicBoolean(false);

    CompilationUnit compilationUnit = makeCompilationUnitFromSource(code);

    ImmutableSortedSet.Builder<String> providedSymbols = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<String> requiredSymbols = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<String> exportedSymbols = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<String> requiredSymbolsFromExplicitImports =
        ImmutableSortedSet.naturalOrder();

    compilationUnit.accept(
        new ASTVisitor() {

          @Nullable private String packageName;

          /** Maps simple name to fully-qualified name. */
          private Map<String, String> simpleImportedTypes = new HashMap<>();

          /**
           * Maps wildcard import prefixes, such as {@code "java.util"} to the types in the
           * respective package if a wildcard import such as {@code import java.util.*} is used.
           */
          private Map<String, ImmutableSet<String>> wildcardImports = new HashMap<>();

          @Override
          public boolean visit(PackageDeclaration node) {
            Preconditions.checkState(
                packageName == null, "There should be at most one package declaration");
            packageName = node.getName().getFullyQualifiedName();
            return false;
          }

          // providedSymbols

          @Override
          public boolean visit(TypeDeclaration node) {
            // Local classes can be declared inside of methods. Skip over these.
            if (node.getParent() instanceof TypeDeclarationStatement) {
              return true;
            }

            String fullyQualifiedName = getFullyQualifiedTypeName(node);
            if (fullyQualifiedName != null) {
              providedSymbols.add(fullyQualifiedName);
            }

            @SuppressWarnings("unchecked")
            List<Type> interfaceTypes = node.superInterfaceTypes();
            for (Type interfaceType : interfaceTypes) {
              tryAddType(interfaceType, DependencyType.EXPORTED);
            }

            Type superclassType = node.getSuperclassType();
            if (superclassType != null) {
              tryAddType(superclassType, DependencyType.EXPORTED);
            }

            return true;
          }

          @Override
          public boolean visit(EnumDeclaration node) {
            String fullyQualifiedName = getFullyQualifiedTypeName(node);
            if (fullyQualifiedName != null) {
              providedSymbols.add(fullyQualifiedName);
            }
            return true;
          }

          @Override
          public boolean visit(AnnotationTypeDeclaration node) {
            String fullyQualifiedName = getFullyQualifiedTypeName(node);
            if (fullyQualifiedName != null) {
              providedSymbols.add(fullyQualifiedName);
            }
            return true;
          }

          // requiredSymbols

          /**
           * Uses heuristics to try to figure out what type of QualifiedName this is. Returns a
           * non-null value if this is believed to be a reference that qualifies as a "required
           * symbol" relationship.
           */
          @Override
          public boolean visit(QualifiedName node) {
            QualifiedName ancestor = findMostQualifiedAncestor(node);
            ASTNode parent = ancestor.getParent();
            if (!(parent instanceof PackageDeclaration) && !(parent instanceof ImportDeclaration)) {
              String symbol = ancestor.getFullyQualifiedName();

              // If it does not start with an uppercase letter, it is probably because it is a
              // property lookup.
              if (CharMatcher.javaUpperCase().matches(symbol.charAt(0))) {
                addTypeFromDotDelimitedSequence(symbol, DependencyType.REQUIRED);
              }
            }

            return false;
          }

          /**
           * @param expr could be "Example", "Example.field", "com.example.Example". Note it could
           *     also be a built-in type, such as "java.lang.Integer", in which case it will not be
           *     added to the set of required symbols.
           */
          private void addTypeFromDotDelimitedSequence(String expr, DependencyType dependencyType) {
            // At this point, symbol could be `System.out`. We want to reduce it to `System` and
            // then check it against JAVA_LANG_TYPES.
            if (startsWithUppercaseChar(expr)) {
              int index = expr.indexOf('.');
              if (index >= 0) {
                String leftmostComponent = expr.substring(0, index);
                if (JAVA_LANG_TYPES.contains(leftmostComponent)) {
                  return;
                }
              }
            }

            expr = qualifyWithPackageNameIfNecessary(expr);
            addSymbol(expr, dependencyType);
          }

          @Override
          public boolean visit(ImportDeclaration node) {
            String fullyQualifiedName = node.getName().getFullyQualifiedName();

            // Apparently, "on demand" means "uses a wildcard," such as "import java.util.*".
            // Although we can choose to prohibit these in our own code, it is much harder to
            // enforce for third-party code. As such, we will tolerate these for some of the common
            // cases.
            if (node.isOnDemand()) {
              ImmutableSet<String> value = SUPPORTED_WILDCARD_IMPORTS.get(fullyQualifiedName);
              if (value != null) {
                wildcardImports.put(fullyQualifiedName, value);
                return false;
              } else if (shouldThrowForUnsupportedWildcardImport) {
                throw new RuntimeException(
                    String.format(
                        "Use of wildcard 'import %s.*' makes it impossible to statically determine "
                            + "required symbols in this file. Please enumerate explicit imports.",
                        fullyQualifiedName));
              } else {
                isPoisonedByUnsupportedWildcardImport.set(true);
                return false;
              }
            }

            // Only worry about the dependency on the enclosing type.
            Optional<String> simpleName = getSimpleNameFromFullyQualifiedName(fullyQualifiedName);
            if (simpleName.isPresent()) {
              String name = simpleName.get();
              int index = fullyQualifiedName.indexOf("." + name);
              String enclosingType = fullyQualifiedName.substring(0, index + name.length() + 1);
              requiredSymbolsFromExplicitImports.add(enclosingType);

              simpleImportedTypes.put(name, enclosingType);
            } else {
              LOG.warn("Suspicious import lacks obvious enclosing type: %s", fullyQualifiedName);
              // The one example we have seen of this in the wild is
              // "org.whispersystems.curve25519.java.curve_sigs". In practice, we still need to add
              // it as a required symbol in this case.
              requiredSymbols.add(fullyQualifiedName);
            }
            return false;
          }

          @Override
          public boolean visit(MethodInvocation node) {
            if (node.getExpression() == null) {
              return true;
            }

            String receiver = node.getExpression().toString();
            if (looksLikeAType(receiver)) {
              addTypeFromDotDelimitedSequence(receiver, DependencyType.REQUIRED);
            }
            return true;
          }

          /** An annotation on a member with zero arguments. */
          @Override
          public boolean visit(MarkerAnnotation node) {
            DependencyType dependencyType = findDependencyTypeForAnnotation(node);
            addSimpleTypeName(node.getTypeName(), dependencyType);
            return true;
          }

          /** An annotation on a member with named arguments. */
          @Override
          public boolean visit(NormalAnnotation node) {
            DependencyType dependencyType = findDependencyTypeForAnnotation(node);
            addSimpleTypeName(node.getTypeName(), dependencyType);
            return true;
          }

          /** An annotation on a member with a single, unnamed argument. */
          @Override
          public boolean visit(SingleMemberAnnotation node) {
            DependencyType dependencyType = findDependencyTypeForAnnotation(node);
            addSimpleTypeName(node.getTypeName(), dependencyType);
            return true;
          }

          private DependencyType findDependencyTypeForAnnotation(Annotation annotation) {
            ASTNode parentNode = annotation.getParent();
            if (parentNode == null) {
              return DependencyType.REQUIRED;
            }

            if (parentNode instanceof BodyDeclaration) {
              // Note that BodyDeclaration is an abstract class. Its subclasses are things like
              // FieldDeclaration and MethodDeclaration. We want to be sure that an annotation on
              // any non-private declaration is considered an exported symbol.
              BodyDeclaration declaration = (BodyDeclaration) parentNode;

              int modifiers = declaration.getModifiers();
              if ((modifiers & Modifier.PRIVATE) == 0) {
                return DependencyType.EXPORTED;
              }
            }
            return DependencyType.REQUIRED;
          }

          @Override
          public boolean visit(SimpleType node) {
            // This method is responsible for finding the overwhelming majority of the required
            // symbols in the AST.
            tryAddType(node, DependencyType.REQUIRED);
            return true;
          }

          // exportedSymbols

          @Override
          public boolean visit(MethodDeclaration node) {
            // Types from private method signatures need not be exported.
            if ((node.getModifiers() & Modifier.PRIVATE) != 0) {
              return true;
            }

            Type returnType = node.getReturnType2();
            if (returnType != null) {
              tryAddType(returnType, DependencyType.EXPORTED);
            }

            @SuppressWarnings("unchecked")
            List<SingleVariableDeclaration> params = node.parameters();
            for (SingleVariableDeclaration decl : params) {
              tryAddType(decl.getType(), DependencyType.EXPORTED);
            }

            @SuppressWarnings("unchecked")
            List<Type> exceptions = node.thrownExceptionTypes();
            for (Type exception : exceptions) {
              tryAddType(exception, DependencyType.EXPORTED);
            }

            return true;
          }

          @Override
          public boolean visit(FieldDeclaration node) {
            // Types from private fields need not be exported.
            if ((node.getModifiers() & Modifier.PRIVATE) == 0) {
              tryAddType(node.getType(), DependencyType.EXPORTED);
            }
            return true;
          }

          private void tryAddType(Type type, DependencyType dependencyType) {
            if (type.isSimpleType()) {
              SimpleType simpleType = (SimpleType) type;
              Name simpleTypeName = simpleType.getName();
              String simpleName = simpleTypeName.toString();

              // For a Type such as IExample<T>, both "IExample" and "T" will be submitted here as
              // simple types. As such, we use this imperfect heuristic to filter out "T" from being
              // added. Note that this will erroneously exclude "URI". In practice, this should
              // generally be OK. For example, assuming "URI" is also imported, then at least it
              // will end up in the set of required symbols. To this end, we perform a second check
              // for "all caps" types to see if there is a corresponding import and if it should be
              // exported rather than simply required.
              if (!CharMatcher.javaUpperCase().matchesAllOf(simpleName)
                  || (dependencyType == DependencyType.EXPORTED
                      && simpleImportedTypes.containsKey(simpleName))) {
                addSimpleTypeName(simpleTypeName, dependencyType);
              }
            } else if (type.isArrayType()) {
              ArrayType arrayType = (ArrayType) type;
              tryAddType(arrayType.getElementType(), dependencyType);
            } else if (type.isParameterizedType()) {
              ParameterizedType parameterizedType = (ParameterizedType) type;
              tryAddType(parameterizedType.getType(), dependencyType);
              @SuppressWarnings("unchecked")
              List<Type> argTypes = parameterizedType.typeArguments();
              for (Type argType : argTypes) {
                tryAddType(argType, dependencyType);
              }
            }
          }

          private void addSimpleTypeName(Name simpleTypeName, DependencyType dependencyType) {
            String simpleName = simpleTypeName.toString();
            if (JAVA_LANG_TYPES.contains(simpleName)) {
              return;
            }

            String fullyQualifiedNameForSimpleName = simpleImportedTypes.get(simpleName);
            if (fullyQualifiedNameForSimpleName != null) {
              // May need to promote from required to exported in this case.
              if (dependencyType == DependencyType.EXPORTED) {
                addSymbol(fullyQualifiedNameForSimpleName, DependencyType.EXPORTED);
              }
              return;
            }

            // For well-behaved source code, this will always be empty, so don't even bother to
            // create the iterator most of the time.
            if (!wildcardImports.isEmpty()) {
              for (Map.Entry<String, ImmutableSet<String>> entry : wildcardImports.entrySet()) {
                Set<String> types = entry.getValue();
                if (types.contains(simpleName)) {
                  String packageName = entry.getKey();
                  addSymbol(packageName + "." + simpleName, dependencyType);
                  return;
                }
              }
            }

            String symbol = simpleTypeName.getFullyQualifiedName();
            symbol = qualifyWithPackageNameIfNecessary(symbol);
            addSymbol(symbol, dependencyType);
          }

          private void addSymbol(String symbol, DependencyType dependencyType) {
            ((dependencyType == DependencyType.REQUIRED) ? requiredSymbols : exportedSymbols)
                .add(symbol);
          }

          private String qualifyWithPackageNameIfNecessary(String symbol) {
            if (!startsWithUppercaseChar(symbol)) {
              return symbol;
            }

            // If the symbol starts with a capital letter, then we assume that it is a reference to
            // a type in the same package.
            int index = symbol.indexOf('.');
            if (index >= 0) {
              symbol = symbol.substring(0, index);
            }
            if (packageName != null) {
              symbol = packageName + "." + symbol;
            }

            return symbol;
          }
        });

    // TODO(mbolin): Special treatment for exportedSymbols when poisoned by wildcard import.
    ImmutableSortedSet<String> totalExportedSymbols = exportedSymbols.build();

    // If we were poisoned by an unsupported wildcard import, then we should rely exclusively on
    // the explicit imports to determine the required symbols.
    Set<String> totalRequiredSymbols = new HashSet<>();
    if (isPoisonedByUnsupportedWildcardImport.get()) {
      totalRequiredSymbols.addAll(requiredSymbolsFromExplicitImports.build());
    } else {
      totalRequiredSymbols.addAll(requiredSymbolsFromExplicitImports.build());
      totalRequiredSymbols.addAll(requiredSymbols.build());
    }
    // Make sure that required and exported symbols are disjoint sets.
    totalRequiredSymbols.removeAll(totalExportedSymbols);

    return new JavaFileFeatures(
        providedSymbols.build(),
        ImmutableSortedSet.copyOf(totalRequiredSymbols),
        totalExportedSymbols);
  }

  private static QualifiedName findMostQualifiedAncestor(QualifiedName node) {
    ASTNode parent = node.getParent();
    if (parent instanceof QualifiedName) {
      return (QualifiedName) parent;
    } else {
      return node;
    }
  }

  /**
   * @return {@link Optional#empty()} if there are no uppercase components in the {@code
   *     fullyQualifiedName}, such as {@code import org.whispersystems.curve25519.java.curve_sigs;}.
   */
  private static Optional<String> getSimpleNameFromFullyQualifiedName(String fullyQualifiedName) {
    int dotIndex = fullyQualifiedName.indexOf('.');
    if (dotIndex < 0) {
      return Optional.of(fullyQualifiedName);
    }

    int startIndex = 0;
    while (dotIndex <= fullyQualifiedName.length()) {
      String component = fullyQualifiedName.substring(startIndex, dotIndex);
      // In practice, if there is an uppercase character in the component, it should be the first
      // character, but we have found some exceptions, in practice.
      if (CharMatcher.javaUpperCase().matchesAnyOf(component)) {
        return Optional.of(component);
      } else {
        startIndex = dotIndex + 1;
        dotIndex = fullyQualifiedName.indexOf('.', startIndex);
        if (dotIndex < 0) {
          int length = fullyQualifiedName.length();
          if (startIndex <= length) {
            dotIndex = length;
          } else {
            break;
          }
        }
      }
    }

    return Optional.empty();
  }

  private static boolean startsWithUppercaseChar(String str) {
    return CharMatcher.javaUpperCase().matches(str.charAt(0));
  }

  private static boolean looksLikeAType(String str) {
    Iterable<String> parts = Splitter.on('.').split(str);
    Iterator<String> iter = parts.iterator();
    boolean hasPartThatStartsWithUppercaseLetter = false;
    while (iter.hasNext()) {
      String part = iter.next();
      Preconditions.checkState(
          !part.isEmpty(), "Dot delimited string should not have an empty segment: '%s'", str);

      // Don't let it start with a digit?
      if (!CharMatcher.javaLetterOrDigit().matchesAllOf(part)) {
        return false;
      } else if (!hasPartThatStartsWithUppercaseLetter) {
        hasPartThatStartsWithUppercaseLetter = CharMatcher.javaUpperCase().matches(part.charAt(0));
      }
    }
    return hasPartThatStartsWithUppercaseLetter;
  }

  public static class JavaFileFeatures {
    public final ImmutableSortedSet<String> providedSymbols;
    public final ImmutableSortedSet<String> requiredSymbols;

    /**
     * Exported symbols are those that need to be on the classpath when compiling against the
     * providedSymbols. These include:
     *
     * <ul>
     *   <li>Parameter types for non-private methods of non-private types.
     *   <li>Return types for non-private methods of non-private types.
     *   <li>Parent classes of non-private provided types.
     *   <li>Parent interfaces of non-private provided types.
     *   <li>Types of non-private fields of non-private types.
     * </ul>
     */
    public final ImmutableSortedSet<String> exportedSymbols;

    private JavaFileFeatures(
        ImmutableSortedSet<String> providedSymbols,
        ImmutableSortedSet<String> requiredSymbols,
        ImmutableSortedSet<String> exportedSymbols) {
      this.providedSymbols = providedSymbols;
      this.requiredSymbols = requiredSymbols;
      this.exportedSymbols = exportedSymbols;
    }

    @Override
    public String toString() {
      return String.format(
          "providedSymbols=%s; requiredSymbols=%s; exportedSymbols=%s",
          providedSymbols, requiredSymbols, exportedSymbols);
    }
  }

  private CompilationUnit makeCompilationUnitFromSource(String code) {
    ASTParser parser = ASTParser.newParser(jlsLevel);
    parser.setSource(code.toCharArray());
    parser.setKind(ASTParser.K_COMPILATION_UNIT);

    Map<String, String> options = JavaCore.getOptions();
    JavaCore.setComplianceOptions(javaVersion, options);
    parser.setCompilerOptions(options);

    return (CompilationUnit) parser.createAST(/* monitor */ null);
  }

  @Nullable
  private String getFullyQualifiedTypeName(AbstractTypeDeclaration node) {
    LinkedList<String> nameParts = new LinkedList<>();
    nameParts.add(node.getName().toString());
    ASTNode parent = node.getParent();
    while (!(parent instanceof CompilationUnit)) {
      if (parent instanceof AbstractTypeDeclaration) {
        nameParts.addFirst(((AbstractTypeDeclaration) parent).getName().toString());
        parent = parent.getParent();
      } else if (parent instanceof AnonymousClassDeclaration) {
        // If this is defined in an anonymous class, then there is no meaningful fully qualified
        // name.
        return null;
      } else {
        throw new RuntimeException("Unexpected parent " + parent + " for " + node);
      }
    }

    // A Java file might not have a package. Hopefully all of ours do though...
    PackageDeclaration packageDecl = ((CompilationUnit) parent).getPackage();
    if (packageDecl != null) {
      nameParts.addFirst(packageDecl.getName().toString());
    }

    return Joiner.on(".").join(nameParts);
  }
}
