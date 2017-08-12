// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import static com.android.tools.r8.utils.DescriptorUtils.javaTypeToDescriptor;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.MemberNaming.Signature.SignatureKind;
import com.android.tools.r8.utils.DescriptorUtils;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import org.objectweb.asm.Type;

/**
 * Stores renaming information for a member.
 * <p>
 * This includes the signature, the original name and inlining range information.
 */
public class MemberNaming {

  private static final int UNDEFINED_START_NUMBER = -1;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MemberNaming)) {
      return false;
    }

    MemberNaming that = (MemberNaming) o;
    return signature.equals(that.signature)
        && renamedSignature.equals(that.renamedSignature)
        && topLevelRange.equals(that.topLevelRange)
        && inlineInformation.equals(that.inlineInformation);
  }

  @Override
  public int hashCode() {
    int result = signature.hashCode();
    result = 31 * result + renamedSignature.hashCode();
    result = 31 * result + inlineInformation.hashCode();
    result = 31 * result + topLevelRange.hashCode();
    return result;
  }

  /**
   * Original signature of the member
   */
  final Signature signature;
  /**
   * Renamed signature where the name (but not the types) have been renamed.
   */
  final Signature renamedSignature;
  public final List<InlineInformation> inlineInformation = new LinkedList<>();
  public final Range topLevelRange;

  private int collapsedStartLineNumber = UNDEFINED_START_NUMBER;
  private int originalStartLineNumber = UNDEFINED_START_NUMBER;

  MemberNaming(Signature signature, String renamedName, Range inlinedLineRange) {
    this.signature = signature;
    this.renamedSignature = signature.asRenamed(renamedName);
    topLevelRange = inlinedLineRange == null ? fakeZeroRange : inlinedLineRange;
  }

  public void addInliningRange(Range inlinedRange, Signature signature, Range originalRange) {
    inlineInformation.add(new InlineInformation(inlinedRange, originalRange, signature));
  }

  public List<Range> getInlineRanges() {
    List<Range> inlineRanges = new ArrayList<>();
    for (InlineInformation information : inlineInformation) {
      if (information.isActualInlining()) {
        inlineRanges.add(information.inlinedRange);
      }
    }
    return inlineRanges;
  }

  public Signature getOriginalSignature() {
    return signature;
  }

  public String getOriginalName() {
    return signature.name;
  }

  public Signature getRenamedSignature() {
    return renamedSignature;
  }

  public String getRenamedName() {
    return renamedSignature.name;
  }

  public void setCollapsedStartLineNumber(int value) {
    assert collapsedStartLineNumber == UNDEFINED_START_NUMBER;
    collapsedStartLineNumber = value;
  }

  public boolean isMethodNaming() {
    return signature.kind() == SignatureKind.METHOD;
  }

  private int getCollapsedStartLineNumber() {
    return collapsedStartLineNumber;
  }

  protected void write(Writer writer, boolean collapseRanges, boolean indent) throws IOException {
    if (indent) {
      writer.append("    ");
    }
    int rangeCounter =
        collapseRanges ? getCollapsedStartLineNumber() : InlineInformation.DO_NOT_COLLAPSE;
    // Avoid printing the range information if there was none in the original file.
    if (topLevelRange != fakeZeroRange || rangeCounter != UNDEFINED_START_NUMBER) {
      if (collapseRanges) {
        // Skip ranges for methods that are used only once, as they do not have debug information.
        if (rangeCounter != UNDEFINED_START_NUMBER) {
          String rangeString = Integer.toString(rangeCounter);
          writer.append(rangeString).append(":").append(rangeString).append(":");
        } else {
          rangeCounter = 0;
        }
      } else {
        writer.append(topLevelRange.toString());
        writer.append(':');
      }
    } else {
      // We might end up in a case where we have no line information for the top entry but still
      // have inline ranges. Just to be sure, set rangeCounter to a useful value.
      if (collapseRanges) {
        rangeCounter = 0;
      }
    }
    signature.write(writer);
    if (originalStartLineNumber != UNDEFINED_START_NUMBER) {
      // If we have original line number information, print it here.
      String originalSourceLineString = Integer.toString(originalStartLineNumber);
      writer.append(':')
          .append(originalSourceLineString)
          .append(':')
          .append(originalSourceLineString);
    }
    writer.append(" -> ");
    writer.append(renamedSignature.name);
    writer.append("\n");
    for (InlineInformation information : inlineInformation) {
      assert !collapseRanges || rangeCounter >= 0;
      if (collapseRanges && information.isActualInlining()) {
        rangeCounter++;
      }
      information.write(writer, rangeCounter, indent);
    }
  }

  @Override
  public String toString() {
    try {
      StringWriter writer = new StringWriter();
      write(writer, false, false);
      return writer.toString();
    } catch (IOException e) {
      return e.toString();
    }
  }

  public void setOriginalStartLineNumber(int originalStartLineNumber) {
    assert this.originalStartLineNumber == UNDEFINED_START_NUMBER;
    this.originalStartLineNumber = originalStartLineNumber;
  }

  public abstract static class Signature {

    public final String name;

    protected Signature(String name) {
      this.name = name;
    }

    abstract Signature asRenamed(String renamedName);

    abstract public SignatureKind kind();

    @Override
    abstract public boolean equals(Object o);

    @Override
    abstract public int hashCode();

    abstract void write(Writer builder) throws IOException;

    @Override
    public String toString() {
      try {
        StringWriter writer = new StringWriter();
        write(writer);
        return writer.toString();
      } catch (IOException e) {
        return e.toString();
      }
    }

    enum SignatureKind {
      METHOD,
      FIELD
    }
  }

  public static class FieldSignature extends Signature {

    public final String type;

    public FieldSignature(String name, String type) {
      super(name);
      this.type = type;
    }

    public static FieldSignature fromDexField(DexField field) {
      return new FieldSignature(field.name.toSourceString(),
          field.type.toSourceString());
    }

    DexField toDexField(DexItemFactory factory, DexType clazz) {
      return factory.createField(
          clazz,
          factory.createType(javaTypeToDescriptor(type)),
          factory.createString(name));
    }

    @Override
    Signature asRenamed(String renamedName) {
      return new FieldSignature(renamedName, type);
    }

    @Override
    public SignatureKind kind() {
      return SignatureKind.FIELD;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FieldSignature)) {
        return false;
      }
      FieldSignature that = (FieldSignature) o;
      return name.equals(that.name) && type.equals(that.type);
    }

    @Override
    public int hashCode() {
      return name.hashCode() * 31 + type.hashCode();
    }

    @Override
    public String toString() {
      return type + " " + name;
    }

    @Override
    void write(Writer writer) throws IOException {
      writer.append(type);
      writer.append(' ');
      writer.append(name);
    }
  }

  public static class MethodSignature extends Signature {

    public final String type;
    public final String[] parameters;

    public MethodSignature(String name, String type, String[] parameters) {
      super(name);
      this.type = type;
      this.parameters = parameters;
    }

    public static MethodSignature fromDexMethod(DexMethod method) {
      String[] paramNames = new String[method.getArity()];
      DexType[] values = method.proto.parameters.values;
      for (int i = 0; i < values.length; i++) {
        paramNames[i] = values[i].toSourceString();
      }
      return new MethodSignature(method.name.toSourceString(),
          method.proto.returnType.toSourceString(), paramNames);
    }

    public static MethodSignature fromSignature(String name, String signature) {
      Type[] parameterDescriptors = Type.getArgumentTypes(signature);
      Type returnDescriptor = Type.getReturnType(signature);
      String[] parameterTypes = new String[parameterDescriptors.length];
      for (int i = 0; i < parameterDescriptors.length; i++) {
        parameterTypes[i] =
            DescriptorUtils.descriptorToJavaType(parameterDescriptors[i].getDescriptor());
      }
      return new MethodSignature(
          name,
          DescriptorUtils.descriptorToJavaType(returnDescriptor.getDescriptor()),
          parameterTypes);
    }

    DexMethod toDexMethod(DexItemFactory factory, DexType clazz) {
      DexType[] paramTypes = new DexType[parameters.length];
      for (int i = 0; i < parameters.length; i++) {
        paramTypes[i] = factory.createType(javaTypeToDescriptor(parameters[i]));
      }
      DexType returnType = factory.createType(javaTypeToDescriptor(type));
      return factory.createMethod(
          clazz,
          factory.createProto(returnType, paramTypes),
          factory.createString(name));
    }

    public static MethodSignature initializer(String[] parameters) {
      return new MethodSignature(Constants.INSTANCE_INITIALIZER_NAME, "void", parameters);
    }

    @Override
    Signature asRenamed(String renamedName) {
      return new MethodSignature(renamedName, type, parameters);
    }

    @Override
    public SignatureKind kind() {
      return SignatureKind.METHOD;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof MethodSignature)) {
        return false;
      }

      MethodSignature that = (MethodSignature) o;
      return type.equals(that.type)
          && name.equals(that.name)
          && Arrays.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
      return (type.hashCode() * 17
          + name.hashCode()) * 31
          + Arrays.hashCode(parameters);
    }

    @Override
    void write(Writer writer) throws IOException {
      writer.append(type)
          .append(' ')
          .append(name)
          .append('(');
      for (int i = 0; i < parameters.length; i++) {
        writer.append(parameters[i]);
        if (i < parameters.length - 1) {
          writer.append(',');
        }
      }
      writer.append(')');
    }
  }

  public class InlineInformation {
    static final int DO_NOT_COLLAPSE = -1;

    public final Range inlinedRange;
    public final Range originalRange;
    public final Signature signature;

    public InlineInformation(Range inlinedRange, Range originalRange, Signature signature) {
      this.inlinedRange = inlinedRange;
      this.originalRange = originalRange;
      this.signature = signature;
    }

    public boolean isActualInlining() {
      return !(originalRange instanceof SingleLineRange);
    }

    public void write(Writer writer, int collapsedRange, boolean indent) throws IOException {
      if (indent) {
        writer.append("    ");
      }
      if (collapsedRange == DO_NOT_COLLAPSE) {
        writer.append(inlinedRange.toString());
      } else {
        writer.append(Range.toCollapsedString(collapsedRange));
      }
      writer.append(":");
      signature.write(writer);
      if (originalRange != null) {
        writer.append(':')
            .append(originalRange.toString());
      }
      writer.append(" -> ")
          .append(renamedSignature.name);
      writer.append("\n");
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof InlineInformation)) {
        return false;
      }

      InlineInformation that = (InlineInformation) o;

      return inlinedRange.equals(that.inlinedRange)
          && ((originalRange == null && that.originalRange == null)
              || originalRange.equals(that.originalRange))
          && signature.equals(that.signature);

    }

    @Override
    public int hashCode() {
      int result = inlinedRange.hashCode();
      result = 31 * result + originalRange.hashCode();
      result = 31 * result + signature.hashCode();
      return result;
    }
  }

  /**
   * Represents a linenumber range.
   */
  public static class Range {

    public final int from;
    public final int to;

    Range(int from, int to) {
      this.from = from;
      this.to = to;
    }

    public boolean contains(int value) {
      return value >= from && value <= to;
    }

    public boolean isSingle() {
      return false;
    }

    @Override
    public String toString() {
      return from + ":" + to;
    }

    public static String toCollapsedString(int value) {
      return value + ":" + value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Range)) {
        return false;
      }

      Range range = (Range) o;
      return from == range.from && to == range.to;
    }

    @Override
    public int hashCode() {
      int result = from;
      result = 31 * result + to;
      return result;
    }

  }

  /**
   * Represents a single linenumber range (':' followed by a signle number), which
   * is different semantically from a normal range that has the same from and to.
   */
  public static class SingleLineRange extends Range {
    public SingleLineRange(int fromAndTo) {
      super(fromAndTo, fromAndTo);
    }

    @Override
    public boolean isSingle() {
      return true;
    }

    @Override
    public String toString() {
      return Integer.toString(from);
    }
  }

  public final static Range fakeZeroRange = new Range(0, 0);
}
