// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_INLINING_CANDIDATE_ANY;
import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_INLINING_CANDIDATE_SAME_CLASS;
import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_INLINING_CANDIDATE_SAME_PACKAGE;
import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_INLINING_CANDIDATE_SUBCLASS;
import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_NOT_INLINING_CANDIDATE;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.code.Const;
import com.android.tools.r8.code.ConstString;
import com.android.tools.r8.code.ConstStringJumbo;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.code.InvokeDirect;
import com.android.tools.r8.code.InvokeStatic;
import com.android.tools.r8.code.NewInstance;
import com.android.tools.r8.code.Throw;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.JumboStringRewriter;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.ValueNumberGenerator;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.ir.optimize.Inliner.Reason;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.ir.synthetic.ForwardMethodSourceCode;
import com.android.tools.r8.ir.synthetic.SynthesizedCode;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.MemberNaming.Signature;
import com.android.tools.r8.utils.InternalOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

public class DexEncodedMethod extends KeyedDexItem<DexMethod> {

  /**
   * Encodes the processing state of a method.
   * <p>
   * We also use this enum to encode whether and if under what constraints a method may be
   * inlined.
   */
  public enum CompilationState {
    /**
     * Has not been processed, yet.
     */
    NOT_PROCESSED,
    /**
     * Has been processed but cannot be inlined due to instructions that are not supported.
     */
    PROCESSED_NOT_INLINING_CANDIDATE,
    /**
     * Code only contains instructions that access public entities and can this be inlined
     * into any context.
     */
    PROCESSED_INLINING_CANDIDATE_ANY,
    /**
     * Code also contains instructions that access protected entities that reside in a differnt
     * package and hence require subclass relationship to be visible.
     */
    PROCESSED_INLINING_CANDIDATE_SUBCLASS,
    /**
     * Code contains instructions that reference package private entities or protected entities
     * from the same package.
     */
    PROCESSED_INLINING_CANDIDATE_SAME_PACKAGE,
    /**
     * Code contains instructions that reference private entities.
     */
    PROCESSED_INLINING_CANDIDATE_SAME_CLASS,
  }

  public static final DexEncodedMethod[] EMPTY_ARRAY = new DexEncodedMethod[]{};
  public static final DexEncodedMethod SENTINEL =
      new DexEncodedMethod(null, null, null, null, null);

  public final DexMethod method;
  public final MethodAccessFlags accessFlags;
  public DexAnnotationSet annotations;
  public DexAnnotationSetRefList parameterAnnotations;
  private Code code;
  private CompilationState compilationState = CompilationState.NOT_PROCESSED;
  private OptimizationInfo optimizationInfo = DefaultOptimizationInfo.DEFAULT;

  // Encodes a mapping from a range of original to emitted line numbers:
  // (originalFirst, originalFirst + length) -> (emittedFirst, emittedFirst + length)
  public static class DebugPositionRange {
    public final int originalFirst;
    public final int emittedFirst;
    private final int length; // 0 for a single line.

    // The original source line numbers are compressed to a tight range
    public DebugPositionRange(int originalFirst, int emittedFirst, int length) {
      assert originalFirst >= 0 && emittedFirst >= 0 && length >= 0;
      this.originalFirst = originalFirst;
      this.length = length;
      this.emittedFirst = emittedFirst;
    }

    public int getOriginalLast() {
      return originalFirst + length;
    }

    public int getEmittedLast() {
      return emittedFirst + length;
    }
  }

  public static class DebugPositionRangeList {
    // Build sorted list of DebugPositionRange objects (sorted by emitted position) from single
    // line-to-line mappings
    public static class Builder {
      private static class Mapping implements Comparable<Mapping> {
        public int original;
        public int emitted;

        Mapping(int original, int emitted) {
          this.original = original;
          this.emitted = emitted;
        }

        @Override
        public int compareTo(Mapping rhs) {
          if (emitted == rhs.emitted) {
            return original - rhs.original;
          } else {
            return emitted - rhs.emitted;
          }
        }
      }

      public void add(int original, int emitted) {
        list.add(new Mapping(original, emitted));
      }

      public List<DebugPositionRange> build() {
        if (list.isEmpty()) {
          return Collections.<DebugPositionRange>emptyList();
        }

        Collections.sort(list);

        List<DebugPositionRange> result = new ArrayList<>();

        ListIterator<Mapping> iterator = list.listIterator();
        Mapping pendingMapping = iterator.next();
        int pendingLength = 0;
        while (iterator.hasNext()) {
          Mapping mapping = iterator.next();
          // Try to merge with last interval.
          int originalDiff = mapping.original - (pendingMapping.original + pendingLength);
          int emittedDiff = mapping.emitted - (pendingMapping.emitted + pendingLength);
          if (originalDiff == 0 && emittedDiff == 0) {
            // Skip duplicates.
            continue;
          }
          assert emittedDiff > 0;
          if (originalDiff == emittedDiff) {
            pendingLength = mapping.original - pendingMapping.original;
          } else {
            result.add(
                new DebugPositionRange(
                    pendingMapping.original, pendingMapping.emitted, pendingLength));
            pendingMapping = mapping;
            pendingLength = 0;
          }
        }
        result.add(
            new DebugPositionRange(pendingMapping.original, pendingMapping.emitted, pendingLength));
        return Collections.unmodifiableList(result);
      }

      private List<Mapping> list = new ArrayList<>();
    }
  }

  public List<DebugPositionRange> debugPositionRangeList = null;

  public DexEncodedMethod(
      DexMethod method,
      MethodAccessFlags accessFlags,
      DexAnnotationSet annotations,
      DexAnnotationSetRefList parameterAnnotations,
      Code code) {
    this.method = method;
    this.accessFlags = accessFlags;
    this.annotations = annotations;
    this.parameterAnnotations = parameterAnnotations;
    this.code = code;
    assert code == null || !accessFlags.isAbstract();
  }

  public boolean isProcessed() {
    return compilationState != CompilationState.NOT_PROCESSED;
  }

  public boolean isInstanceInitializer() {
    return accessFlags.isConstructor() && !accessFlags.isStatic();
  }

  public boolean isDefaultInitializer() {
    return isInstanceInitializer() && method.proto.parameters.isEmpty();
  }

  public boolean isClassInitializer() {
    return accessFlags.isConstructor() && accessFlags.isStatic();
  }

  public boolean isInliningCandidate(DexEncodedMethod container, Reason inliningReason,
      AppInfoWithSubtyping appInfo) {
    if (isClassInitializer()) {
      // This will probably never happen but never inline a class initializer.
      return false;
    }
    if (inliningReason == Reason.FORCE) {
      return true;
    }
    switch (compilationState) {
      case PROCESSED_INLINING_CANDIDATE_ANY:
        return true;
      case PROCESSED_INLINING_CANDIDATE_SUBCLASS:
        return container.method.getHolder().isSubtypeOf(method.getHolder(), appInfo);
      case PROCESSED_INLINING_CANDIDATE_SAME_PACKAGE:
        return container.method.getHolder().isSamePackage(method.getHolder());
      case PROCESSED_INLINING_CANDIDATE_SAME_CLASS:
        return container.method.getHolder() == method.getHolder();
      default:
        return false;
    }
  }

  public boolean markProcessed(Constraint state) {
    CompilationState prevCompilationState = compilationState;
    switch (state) {
      case ALWAYS:
        compilationState = PROCESSED_INLINING_CANDIDATE_ANY;
        break;
      case SUBCLASS:
        compilationState = PROCESSED_INLINING_CANDIDATE_SUBCLASS;
        break;
      case PACKAGE:
        compilationState = PROCESSED_INLINING_CANDIDATE_SAME_PACKAGE;
        break;
      case SAMECLASS:
        compilationState = PROCESSED_INLINING_CANDIDATE_SAME_CLASS;
        break;
      case NEVER:
        compilationState = PROCESSED_NOT_INLINING_CANDIDATE;
        break;
    }
    return prevCompilationState != compilationState;
  }

  public void markNotProcessed() {
    compilationState = CompilationState.NOT_PROCESSED;
  }

  public IRCode buildIR(InternalOptions options) throws ApiLevelException {
    return code == null ? null : code.buildIR(this, options);
  }

  public IRCode buildIR(InternalOptions options, ValueNumberGenerator valueNumberGenerator)
      throws ApiLevelException {
    return code == null
        ? null
        : code.asDexCode().buildIR(this, options, valueNumberGenerator);
  }

  public void setCode(Code code) {
    this.code = code;
  }

  public void setCode(
      IRCode ir,
      RegisterAllocator registerAllocator,
      InternalOptions options) {
    final DexBuilder builder = new DexBuilder(ir, registerAllocator, options);
    code = builder.build(method.getArity());
  }

  @Override
  public String toString() {
    return "Encoded method " + method;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    method.collectIndexedItems(indexedItems);
    if (code != null) {
      code.collectIndexedItems(indexedItems);
    }
    annotations.collectIndexedItems(indexedItems);
    parameterAnnotations.collectIndexedItems(indexedItems);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    if (code != null) {
      code.collectMixedSectionItems(mixedItems);
    }
    annotations.collectMixedSectionItems(mixedItems);
    parameterAnnotations.collectMixedSectionItems(mixedItems);
  }

  public Code getCode() {
    return code;
  }

  public void setDexCode(DexCode code) {
    this.code = code;
  }

  public void removeCode() {
    code = null;
  }

  public boolean hasDebugPositions() {
    assert code != null && code.isDexCode();
    return code.asDexCode().hasDebugPositions();
  }

  public String qualifiedName() {
    return method.qualifiedName();
  }

  public String descriptor() {
    StringBuilder builder = new StringBuilder();
    builder.append("(");
    for (DexType type : method.proto.parameters.values) {
      builder.append(type.descriptor.toString());
    }
    builder.append(")");
    builder.append(method.proto.returnType.descriptor.toString());
    return builder.toString();
  }

  public String toSmaliString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    builder.append(".method ");
    builder.append(accessFlags.toSmaliString());
    builder.append(" ");
    builder.append(method.name.toSmaliString());
    builder.append(method.proto.toSmaliString());
    builder.append("\n");
    if (code != null) {
      DexCode dexCode = code.asDexCode();
      builder.append("    .registers ");
      builder.append(dexCode.registerSize);
      builder.append("\n\n");
      builder.append(dexCode.toSmaliString(naming));
    }
    builder.append(".end method\n");
    return builder.toString();
  }

  @Override
  public String toSourceString() {
    return method.toSourceString();
  }

  public DexEncodedMethod toAbstractMethod() {
    accessFlags.setAbstract();
    this.code = null;
    return this;
  }

  /**
   * Generates a {@link DexCode} object for the given instructions.
   * <p>
   * As the code object is produced outside of the normal compilation cycle, it has to use
   * {@link ConstStringJumbo} to reference string constants. Hence, code produced form these
   * templates might incur a size overhead.
   */
  private DexCode generateCodeFromTemplate(
      int numberOfRegisters, int outRegisters, Instruction... instructions) {
    int offset = 0;
    for (Instruction instruction : instructions) {
      assert !(instruction instanceof ConstString);
      instruction.setOffset(offset);
      offset += instruction.getSize();
    }
    int requiredArgRegisters = accessFlags.isStatic() ? 0 : 1;
    for (DexType type : method.proto.parameters.values) {
      requiredArgRegisters += ValueType.fromDexType(type).requiredRegisters();
    }
    // Passing null as highestSortingString is save, as ConstString instructions are not allowed.
    return new DexCode(Math.max(numberOfRegisters, requiredArgRegisters), requiredArgRegisters,
        outRegisters, instructions, new DexCode.Try[0], new DexCode.TryHandler[0], null, null);
  }

  public DexEncodedMethod toEmptyThrowingMethod() {
    Instruction insn[] = {new Const(0, 0), new Throw(0)};
    DexCode code = generateCodeFromTemplate(1, 0, insn);
    assert !accessFlags.isAbstract();
    Builder builder = builder(this);
    builder.setCode(code);
    return builder.build();
  }

  public DexEncodedMethod toMethodThatLogsError(DexItemFactory itemFactory) {
    Signature signature = MethodSignature.fromDexMethod(method);
    // TODO(herhut): Construct this out of parts to enable reuse, maybe even using descriptors.
    DexString message = itemFactory.createString(
        "Shaking error: Missing method in " + method.holder.toSourceString() + ": "
            + signature);
    DexString tag = itemFactory.createString("TOIGHTNESS");
    DexType[] args = {itemFactory.stringType, itemFactory.stringType};
    DexProto proto = itemFactory.createProto(itemFactory.intType, args);
    DexMethod logMethod = itemFactory
        .createMethod(itemFactory.createType("Landroid/util/Log;"), proto,
            itemFactory.createString("e"));
    DexType exceptionType = itemFactory.createType("Ljava/lang/RuntimeException;");
    DexMethod exceptionInitMethod = itemFactory
        .createMethod(exceptionType, itemFactory.createProto(itemFactory.voidType,
            itemFactory.stringType),
            itemFactory.constructorMethodName);
    DexCode code;
    if (isInstanceInitializer()) {
      // The Java VM Spec requires that a constructor calls an initializer from the super class
      // or another constructor from the current class. For simplicity we do the latter by just
      // calling ourself. This is ok, as the constructor always throws before the recursive call.
      code = generateCodeFromTemplate(3, 2, new ConstStringJumbo(0, tag),
          new ConstStringJumbo(1, message),
          new InvokeStatic(2, logMethod, 0, 1, 0, 0, 0),
          new NewInstance(0, exceptionType),
          new InvokeDirect(2, exceptionInitMethod, 0, 1, 0, 0, 0),
          new Throw(0),
          new InvokeDirect(1, method, 2, 0, 0, 0, 0));

    } else {
      // These methods might not get registered for jumbo string processing, therefore we always
      // use the jumbo string encoding for the const string instruction.
      code = generateCodeFromTemplate(2, 2, new ConstStringJumbo(0, tag),
          new ConstStringJumbo(1, message),
          new InvokeStatic(2, logMethod, 0, 1, 0, 0, 0),
          new NewInstance(0, exceptionType),
          new InvokeDirect(2, exceptionInitMethod, 0, 1, 0, 0, 0),
          new Throw(0));
    }
    Builder builder = builder(this);
    builder.setCode(code);
    return builder.build();
  }

  public DexEncodedMethod toTypeSubstitutedMethod(DexMethod method) {
    if (this.method == method) {
      return this;
    }
    Builder builder = builder(this);
    builder.setMethod(method);
    return builder.build();
  }

  public DexEncodedMethod toRenamedMethod(DexString name, DexItemFactory factory) {
    if (method.name == name) {
      return this;
    }
    DexMethod newMethod = factory.createMethod(method.holder, method.proto, name);
    Builder builder = builder(this);
    builder.setMethod(newMethod);
    return builder.build();
  }

  public DexEncodedMethod toForwardingMethod(DexClass holder, DexItemFactory itemFactory) {
    assert accessFlags.isPublic();
    // Clear the final flag, as this method is now overwritten. Do this before creating the builder
    // for the forwarding method, as the forwarding method will copy the access flags from this,
    // and if different forwarding methods are created in different subclasses the first could be
    // final.
    accessFlags.unsetFinal();
    DexMethod newMethod = itemFactory.createMethod(holder.type, method.proto, method.name);
    Invoke.Type type = accessFlags.isStatic() ? Invoke.Type.STATIC : Invoke.Type.SUPER;
    Builder builder = builder(this);
    builder.setMethod(newMethod);
    if (accessFlags.isAbstract()) {
      // If the forwarding target is abstract, we can just create an abstract method. While it
      // will not actually forward, it will create the same exception when hit at runtime.
      builder.accessFlags.setAbstract();
    } else {
      // Create code that forwards the call to the target.
      builder.setCode(new SynthesizedCode(
          new ForwardMethodSourceCode(
              accessFlags.isStatic() ? null : holder.type,
              method.proto,
              accessFlags.isStatic() ? null : method.holder,
              method,
              type),
          registry -> {
            if (accessFlags.isStatic()) {
              registry.registerInvokeStatic(method);
            } else {
              registry.registerInvokeSuper(method);
            }
          }));
    }
    builder.accessFlags.setSynthetic();
    return builder.build();
  }

  /**
   * Rewrites the code in this method to have JumboString bytecode if required by mapping.
   * <p>
   * Synchronized such that it can be called concurrently for different mappings. As a side-effect,
   * this will also update the highestSortingString to the index of the strings up to which the
   * code was rewritten to avoid rewriting again unless needed.
   */
  public synchronized void rewriteCodeWithJumboStrings(ObjectToOffsetMapping mapping,
      DexApplication application) {
    assert code == null || code.isDexCode();
    if (code == null) {
      return;
    }
    DexCode code = this.code.asDexCode();
    if (code.highestSortingString != null) {
      if (mapping.getOffsetFor(code.highestSortingString) > Constants.MAX_NON_JUMBO_INDEX) {
        JumboStringRewriter rewriter =
            new JumboStringRewriter(this, mapping.getFirstJumboString(),
                application.dexItemFactory);
        rewriter.rewrite();
      }
    }
  }

  public String codeToString() {
    return code == null ? "<no code>" : code.toString(this, null);
  }

  @Override
  public DexMethod getKey() {
    return method;
  }

  public boolean hasAnnotation() {
    return !annotations.isEmpty() || !parameterAnnotations.isEmpty();
  }

  public void registerReachableDefinitions(UseRegistry registry) {
    if (code != null) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Registering definitions reachable from `%s`.", method);
      }
      code.registerReachableDefinitions(registry);
    }
  }

  public static int slowCompare(DexEncodedMethod m1, DexEncodedMethod m2) {
    return m1.method.slowCompareTo(m2.method);
  }

  public static class OptimizationInfo {

    private int returnedArgument = -1;
    private boolean neverReturnsNull = false;
    private boolean returnsConstant = false;
    private long returnedConstant = 0;
    private boolean forceInline = false;

    private OptimizationInfo() {
      // Intentionally left empty.
    }

    private OptimizationInfo(OptimizationInfo template) {
      returnedArgument = template.returnedArgument;
      neverReturnsNull = template.neverReturnsNull;
      returnsConstant = template.returnsConstant;
      returnedConstant = template.returnedConstant;
      forceInline = template.forceInline;
    }

    public boolean returnsArgument() {
      return returnedArgument != -1;
    }

    public int getReturnedArgument() {
      assert returnsArgument();
      return returnedArgument;
    }

    public boolean neverReturnsNull() {
      return neverReturnsNull;
    }

    public boolean returnsConstant() {
      return returnsConstant;
    }

    public long getReturnedConstant() {
      assert returnsConstant();
      return returnedConstant;
    }

    public boolean forceInline() {
      return forceInline;
    }

    private void markReturnsArgument(int argument) {
      assert argument >= 0;
      assert returnedArgument == -1 || returnedArgument == argument;
      returnedArgument = argument;
    }

    private void markNeverReturnsNull() {
      neverReturnsNull = true;
    }

    private void markReturnsConstant(long value) {
      assert !returnsConstant || returnedConstant == value;
      returnsConstant = true;
      returnedConstant = value;
    }

    private void markForceInline() {
      forceInline = true;
    }

    public OptimizationInfo copy() {
      return new OptimizationInfo(this);
    }
  }

  private static class DefaultOptimizationInfo extends OptimizationInfo {

    static final OptimizationInfo DEFAULT = new DefaultOptimizationInfo();

    private DefaultOptimizationInfo() {
    }

    @Override
    public OptimizationInfo copy() {
      return this;
    }
  }

  synchronized private OptimizationInfo ensureMutableOI() {
    if (optimizationInfo == DefaultOptimizationInfo.DEFAULT) {
      optimizationInfo = new OptimizationInfo();
    }
    return optimizationInfo;
  }

  synchronized public void markReturnsArgument(int argument) {
    ensureMutableOI().markReturnsArgument(argument);
  }

  synchronized public void markNeverReturnsNull() {
    ensureMutableOI().markNeverReturnsNull();
  }

  synchronized public void markReturnsConstant(long value) {
    ensureMutableOI().markReturnsConstant(value);
  }

  synchronized public void markForceInline() {
    ensureMutableOI().markForceInline();
  }

  public OptimizationInfo getOptimizationInfo() {
    return optimizationInfo;
  }

  private static Builder builder(DexEncodedMethod from) {
    return new Builder(from);
  }

  private static class Builder {

    private DexMethod method;
    private MethodAccessFlags accessFlags;
    private DexAnnotationSet annotations;
    private DexAnnotationSetRefList parameterAnnotations;
    private Code code;
    private CompilationState compilationState = CompilationState.NOT_PROCESSED;
    private OptimizationInfo optimizationInfo = DefaultOptimizationInfo.DEFAULT;

    private Builder(DexEncodedMethod from) {
      // Copy all the mutable state of a DexEncodedMethod here.
      method = from.method;
      accessFlags = from.accessFlags.copy();
      annotations = from.annotations;
      parameterAnnotations = from.parameterAnnotations;
      code = from.code;
      compilationState = from.compilationState;
      optimizationInfo = from.optimizationInfo.copy();
    }

    public void setMethod(DexMethod method) {
      this.method = method;
    }

    public void setCode(Code code) {
      this.code = code;
    }

    public DexEncodedMethod build() {
      assert method != null;
      assert accessFlags != null;
      assert annotations != null;
      assert parameterAnnotations != null;
      DexEncodedMethod result =
          new DexEncodedMethod(method, accessFlags, annotations, parameterAnnotations, code);
      result.compilationState = compilationState;
      result.optimizationInfo = optimizationInfo;
      return result;
    }
  }
}
