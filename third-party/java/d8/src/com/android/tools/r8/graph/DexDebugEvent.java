// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.DebugBytecodeWriter;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;

abstract public class DexDebugEvent extends DexItem {

  @Override
  public void collectIndexedItems(IndexedItemCollection collection) {
    // Empty by default.
  }

  @Override
  public void collectMixedSectionItems(MixedSectionCollection collection) {
    // Empty by default.
  }

  // Make sure all concrete subclasses implements toString, hashCode, and equals.
  @Override
  abstract public String toString();

  @Override
  abstract public int hashCode();

  @Override
  abstract public boolean equals(Object other);

  public abstract void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping);

  public abstract void addToBuilder(DexDebugEntryBuilder builder);

  public static class AdvancePC extends DexDebugEvent {

    public final int delta;

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(Constants.DBG_ADVANCE_PC);
      writer.putUleb128(delta);
    }

    AdvancePC(int delta) {
      this.delta = delta;
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      assert delta >= 0;
      builder.advancePC(delta);
    }

    @Override
    public String toString() {
      return "ADVANCE_PC " + delta;
    }

    @Override
    public int hashCode() {
      return Constants.DBG_ADVANCE_PC
          + delta * 7;
    }

    @Override
    public boolean equals(Object other) {
      return (other instanceof AdvancePC)
          && (delta == ((AdvancePC) other).delta);
    }
  }

  public static class SetPrologueEnd extends DexDebugEvent {

    SetPrologueEnd() {
    }

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(Constants.DBG_SET_PROLOGUE_END);
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      builder.endPrologue();
    }

    @Override
    public String toString() {
      return "SET_PROLOGUE_END";
    }


    @Override
    public int hashCode() {
      return Constants.DBG_SET_PROLOGUE_END;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof SetPrologueEnd;
    }
  }


  public static class SetEpilogueBegin extends DexDebugEvent {

    SetEpilogueBegin() {
    }

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(Constants.DBG_SET_EPILOGUE_BEGIN);
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      builder.beginEpilogue();
    }

    @Override
    public String toString() {
      return "SET_EPILOGUE_BEGIN";
    }

    @Override
    public int hashCode() {
      return Constants.DBG_SET_EPILOGUE_BEGIN;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof SetEpilogueBegin;
    }
  }

  public static class AdvanceLine extends DexDebugEvent {

    final int delta;

    AdvanceLine(int delta) {
      this.delta = delta;
    }

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(Constants.DBG_ADVANCE_LINE);
      writer.putSleb128(delta);
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      builder.advanceLine(delta);
    }

    @Override
    public String toString() {
      return "ADVANCE_LINE " + delta;
    }

    @Override
    public int hashCode() {
      return Constants.DBG_ADVANCE_LINE
          + delta * 7;
    }

    @Override
    public boolean equals(Object other) {
      return (other instanceof AdvanceLine)
          && (delta == ((AdvanceLine) other).delta);
    }
  }

  static public class StartLocal extends DexDebugEvent {

    final int registerNum;
    final DexString name;
    final DexType type;
    final DexString signature;

    public StartLocal(
        int registerNum,
        DexString name,
        DexType type,
        DexString signature) {
      this.registerNum = registerNum;
      this.name = name;
      this.type = type;
      this.signature = signature;
    }

    public StartLocal(int registerNum, DebugLocalInfo local) {
      this(registerNum, local.name, local.type, local.signature);
    }

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(signature == null
          ? Constants.DBG_START_LOCAL
          : Constants.DBG_START_LOCAL_EXTENDED);
      writer.putUleb128(registerNum);
      writer.putString(name);
      writer.putType(type);
      if (signature != null) {
        writer.putString(signature);
      }
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection collection) {
      name.collectIndexedItems(collection);
      type.collectIndexedItems(collection);
      if (signature != null) {
        signature.collectIndexedItems(collection);
      }
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      builder.startLocal(registerNum, name, type, signature);
    }

    @Override
    public String toString() {
      return "START_LOCAL " + registerNum;
    }

    @Override
    public int hashCode() {
      return Constants.DBG_START_LOCAL
          + registerNum * 7
          + name.hashCode() * 13
          + type.hashCode() * 17
          + (signature == null ? 0 : signature.hashCode()) * 19;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StartLocal)) {
        return false;
      }
      StartLocal o = (StartLocal) other;
      if (registerNum != o.registerNum) {
        return false;
      }
      if (!name.equals(o.name)) {
        return false;
      }
      if (!type.equals(o.type)) {
        return false;
      }
      return (signature == o.signature || signature.equals(o.signature));
    }
  }

  public static class EndLocal extends DexDebugEvent {

    final int registerNum;

    EndLocal(int registerNum) {
      this.registerNum = registerNum;
    }

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(Constants.DBG_END_LOCAL);
      writer.putUleb128(registerNum);
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      builder.endLocal(registerNum);
    }

    @Override
    public String toString() {
      return "END_LOCAL " + registerNum;
    }

    @Override
    public int hashCode() {
      return Constants.DBG_END_LOCAL
          + registerNum * 7;
    }

    @Override
    public boolean equals(Object other) {
      return (other instanceof EndLocal)
          && (registerNum == ((EndLocal) other).registerNum);
    }
  }

  public static class RestartLocal extends DexDebugEvent {

    final int registerNum;

    RestartLocal(int registerNum) {
      this.registerNum = registerNum;
    }

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(Constants.DBG_RESTART_LOCAL);
      writer.putUleb128(registerNum);
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      builder.restartLocal(registerNum);
    }

    @Override
    public String toString() {
      return "RESTART_LOCAL " + registerNum;
    }

    @Override
    public int hashCode() {
      return Constants.DBG_RESTART_LOCAL
          + registerNum * 7;
    }

    @Override
    public boolean equals(Object other) {
      return (other instanceof RestartLocal)
          && (registerNum == ((RestartLocal) other).registerNum);
    }
  }

  public static class SetFile extends DexDebugEvent {

    DexString fileName;

    SetFile(DexString fileName) {
      this.fileName = fileName;
    }

    public DexString getFileName() {
      return fileName;
    }

    public void setFileName(DexString fileName) {
      this.fileName = fileName;
    }

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(Constants.DBG_SET_FILE);
      writer.putString(fileName);
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection collection) {
      fileName.collectIndexedItems(collection);
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      builder.setFile(fileName);
    }

    @Override
    public String toString() {
      return "SET_FILE " + fileName.toString();
    }

    @Override
    public int hashCode() {
      return Constants.DBG_SET_FILE
          + fileName.hashCode() * 7;
    }

    @Override
    public boolean equals(Object other) {
      return (other instanceof SetFile)
          && fileName.equals(((SetFile) other).fileName);
    }
  }

  public static class Default extends DexDebugEvent {

    final int value;

    Default(int value) {
      assert (value >= Constants.DBG_FIRST_SPECIAL) && (value <= Constants.DBG_LAST_SPECIAL);
      this.value = value;
    }

    @Override
    public void writeOn(DebugBytecodeWriter writer, ObjectToOffsetMapping mapping) {
      writer.putByte(value);
    }

    @Override
    public void addToBuilder(DexDebugEntryBuilder builder) {
      int adjustedOpcode = value - Constants.DBG_FIRST_SPECIAL;
      int line = Constants.DBG_LINE_BASE + (adjustedOpcode % Constants.DBG_LINE_RANGE);
      int address = adjustedOpcode / Constants.DBG_LINE_RANGE;
      builder.setPosition(address, line);
    }

    public int getPCDelta() {
      int adjustedOpcode = value - Constants.DBG_FIRST_SPECIAL;
      return adjustedOpcode / Constants.DBG_LINE_RANGE;
    }

    public int getLineDelta() {
      int adjustedOpcode = value - Constants.DBG_FIRST_SPECIAL;
      return Constants.DBG_LINE_BASE + (adjustedOpcode % Constants.DBG_LINE_RANGE);
    }

    @Override
    public String toString() {
      return "DEFAULT " + value;
    }

    @Override
    public int hashCode() {
      return Constants.DBG_FIRST_SPECIAL
          + value * 7;
    }

    @Override
    public boolean equals(Object other) {
      return (other instanceof Default)
          && (value == ((Default) other).value);
    }
  }
}
