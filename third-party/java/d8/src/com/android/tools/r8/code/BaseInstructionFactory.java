// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.graph.OffsetToObjectMapping;

abstract class BaseInstructionFactory {

  static Instruction create(int high, int opcode, BytecodeStream stream,
      OffsetToObjectMapping mapping) {
    switch (opcode) {
      case 0x0:
        return Nop.create(high, stream);
      case Move.OPCODE:
        return new Move(high, stream);
      case MoveFrom16.OPCODE:
        return new MoveFrom16(high, stream);
      case Move16.OPCODE:
        return new Move16(high, stream);
      case MoveWide.OPCODE:
        return new MoveWide(high, stream);
      case MoveWideFrom16.OPCODE:
        return new MoveWideFrom16(high, stream);
      case MoveWide16.OPCODE:
        return new MoveWide16(high, stream);
      case MoveObject.OPCODE:
        return new MoveObject(high, stream);
      case MoveObjectFrom16.OPCODE:
        return new MoveObjectFrom16(high, stream);
      case MoveObject16.OPCODE:
        return new MoveObject16(high, stream);
      case MoveResult.OPCODE:
        return new MoveResult(high, stream);
      case MoveResultWide.OPCODE:
        return new MoveResultWide(high, stream);
      case MoveResultObject.OPCODE:
        return new MoveResultObject(high, stream);
      case MoveException.OPCODE:
        return new MoveException(high, stream);
      case ReturnVoid.OPCODE:
        return new ReturnVoid(high, stream);
      case Return.OPCODE:
        return new Return(high, stream);
      case ReturnWide.OPCODE:
        return new ReturnWide(high, stream);
      case ReturnObject.OPCODE:
        return new ReturnObject(high, stream);
      case Const4.OPCODE:
        return new Const4(high, stream);
      case Const16.OPCODE:
        return new Const16(high, stream);
      case Const.OPCODE:
        return new Const(high, stream);
      case ConstHigh16.OPCODE:
        return new ConstHigh16(high, stream);
      case ConstWide16.OPCODE:
        return new ConstWide16(high, stream);
      case ConstWide32.OPCODE:
        return new ConstWide32(high, stream);
      case ConstWide.OPCODE:
        return new ConstWide(high, stream);
      case ConstWideHigh16.OPCODE:
        return new ConstWideHigh16(high, stream);
      case ConstString.OPCODE:
        return new ConstString(high, stream, mapping);
      case ConstStringJumbo.OPCODE:
        return new ConstStringJumbo(high, stream, mapping);
      case ConstClass.OPCODE:
        return new ConstClass(high, stream, mapping);
      case MonitorEnter.OPCODE:
        return new MonitorEnter(high, stream);
      case MonitorExit.OPCODE:
        return new MonitorExit(high, stream);
      case CheckCast.OPCODE:
        return new CheckCast(high, stream, mapping);
      case InstanceOf.OPCODE:
        return new InstanceOf(high, stream, mapping);
      case ArrayLength.OPCODE:
        return new ArrayLength(high, stream);
      case NewInstance.OPCODE:
        return new NewInstance(high, stream, mapping);
      case NewArray.OPCODE:
        return new NewArray(high, stream, mapping);
      case FilledNewArray.OPCODE:
        return new FilledNewArray(high, stream, mapping);
      case FilledNewArrayRange.OPCODE:
        return new FilledNewArrayRange(high, stream, mapping);
      case FillArrayData.OPCODE:
        return new FillArrayData(high, stream);
      case Throw.OPCODE:
        return new Throw(high, stream);
      case Goto.OPCODE:
        return new Goto(high, stream);
      case Goto16.OPCODE:
        return new Goto16(high, stream);
      case Goto32.OPCODE:
        return new Goto32(high, stream);
      case PackedSwitch.OPCODE:
        return new PackedSwitch(high, stream);
      case SparseSwitch.OPCODE:
        return new SparseSwitch(high, stream);
      case CmplFloat.OPCODE:
        return new CmplFloat(high, stream);
      case CmpgFloat.OPCODE:
        return new CmpgFloat(high, stream);
      case CmplDouble.OPCODE:
        return new CmplDouble(high, stream);
      case CmpgDouble.OPCODE:
        return new CmpgDouble(high, stream);
      case CmpLong.OPCODE:
        return new CmpLong(high, stream);
      case IfEq.OPCODE:
        return new IfEq(high, stream);
      case IfNe.OPCODE:
        return new IfNe(high, stream);
      case IfLt.OPCODE:
        return new IfLt(high, stream);
      case IfGe.OPCODE:
        return new IfGe(high, stream);
      case IfGt.OPCODE:
        return new IfGt(high, stream);
      case IfLe.OPCODE:
        return new IfLe(high, stream);
      case IfEqz.OPCODE:
        return new IfEqz(high, stream);
      case IfNez.OPCODE:
        return new IfNez(high, stream);
      case IfLtz.OPCODE:
        return new IfLtz(high, stream);
      case IfGez.OPCODE:
        return new IfGez(high, stream);
      case IfGtz.OPCODE:
        return new IfGtz(high, stream);
      case IfLez.OPCODE:
        return new IfLez(high, stream);
      case Aget.OPCODE:
        return new Aget(high, stream);
      case AgetWide.OPCODE:
        return new AgetWide(high, stream);
      case AgetObject.OPCODE:
        return new AgetObject(high, stream);
      case AgetBoolean.OPCODE:
        return new AgetBoolean(high, stream);
      case AgetByte.OPCODE:
        return new AgetByte(high, stream);
      case AgetChar.OPCODE:
        return new AgetChar(high, stream);
      case AgetShort.OPCODE:
        return new AgetShort(high, stream);
      case Aput.OPCODE:
        return new Aput(high, stream);
      case AputWide.OPCODE:
        return new AputWide(high, stream);
      case AputObject.OPCODE:
        return new AputObject(high, stream);
      case AputBoolean.OPCODE:
        return new AputBoolean(high, stream);
      case AputByte.OPCODE:
        return new AputByte(high, stream);
      case AputChar.OPCODE:
        return new AputChar(high, stream);
      case AputShort.OPCODE:
        return new AputShort(high, stream);
      case Iget.OPCODE:
        return new Iget(high, stream, mapping);
      case IgetWide.OPCODE:
        return new IgetWide(high, stream, mapping);
      case IgetObject.OPCODE:
        return new IgetObject(high, stream, mapping);
      case IgetBoolean.OPCODE:
        return new IgetBoolean(high, stream, mapping);
      case IgetByte.OPCODE:
        return new IgetByte(high, stream, mapping);
      case IgetChar.OPCODE:
        return new IgetChar(high, stream, mapping);
      case IgetShort.OPCODE:
        return new IgetShort(high, stream, mapping);
      case Iput.OPCODE:
        return new Iput(high, stream, mapping);
      case IputWide.OPCODE:
        return new IputWide(high, stream, mapping);
      case IputObject.OPCODE:
        return new IputObject(high, stream, mapping);
      case IputBoolean.OPCODE:
        return new IputBoolean(high, stream, mapping);
      case IputByte.OPCODE:
        return new IputByte(high, stream, mapping);
      case IputChar.OPCODE:
        return new IputChar(high, stream, mapping);
      case IputShort.OPCODE:
        return new IputShort(high, stream, mapping);
      case Sget.OPCODE:
        return new Sget(high, stream, mapping);
      case SgetWide.OPCODE:
        return new SgetWide(high, stream, mapping);
      case SgetObject.OPCODE:
        return new SgetObject(high, stream, mapping);
      case SgetBoolean.OPCODE:
        return new SgetBoolean(high, stream, mapping);
      case SgetByte.OPCODE:
        return new SgetByte(high, stream, mapping);
      case SgetChar.OPCODE:
        return new SgetChar(high, stream, mapping);
      case SgetShort.OPCODE:
        return new SgetShort(high, stream, mapping);
      case Sput.OPCODE:
        return new Sput(high, stream, mapping);
      case SputWide.OPCODE:
        return new SputWide(high, stream, mapping);
      case SputObject.OPCODE:
        return new SputObject(high, stream, mapping);
      case SputBoolean.OPCODE:
        return new SputBoolean(high, stream, mapping);
      case SputByte.OPCODE:
        return new SputByte(high, stream, mapping);
      case SputChar.OPCODE:
        return new SputChar(high, stream, mapping);
      case SputShort.OPCODE:
        return new SputShort(high, stream, mapping);
      case InvokeVirtual.OPCODE:
        return new InvokeVirtual(high, stream, mapping);
      case InvokeSuper.OPCODE:
        return new InvokeSuper(high, stream, mapping);
      case InvokeDirect.OPCODE:
        return new InvokeDirect(high, stream, mapping);
      case InvokeStatic.OPCODE:
        return new InvokeStatic(high, stream, mapping);
      case InvokeInterface.OPCODE:
        return new InvokeInterface(high, stream, mapping);
      case InvokeVirtualRange.OPCODE:
        return new InvokeVirtualRange(high, stream, mapping);
      case InvokeSuperRange.OPCODE:
        return new InvokeSuperRange(high, stream, mapping);
      case InvokeDirectRange.OPCODE:
        return new InvokeDirectRange(high, stream, mapping);
      case InvokeStaticRange.OPCODE:
        return new InvokeStaticRange(high, stream, mapping);
      case InvokeInterfaceRange.OPCODE:
        return new InvokeInterfaceRange(high, stream, mapping);
      case NegInt.OPCODE:
        return new NegInt(high, stream);
      case NotInt.OPCODE:
        return new NotInt(high, stream);
      case NegLong.OPCODE:
        return new NegLong(high, stream);
      case NotLong.OPCODE:
        return new NotLong(high, stream);
      case NegFloat.OPCODE:
        return new NegFloat(high, stream);
      case NegDouble.OPCODE:
        return new NegDouble(high, stream);
      case IntToLong.OPCODE:
        return new IntToLong(high, stream);
      case IntToFloat.OPCODE:
        return new IntToFloat(high, stream);
      case IntToDouble.OPCODE:
        return new IntToDouble(high, stream);
      case LongToInt.OPCODE:
        return new LongToInt(high, stream);
      case LongToFloat.OPCODE:
        return new LongToFloat(high, stream);
      case LongToDouble.OPCODE:
        return new LongToDouble(high, stream);
      case FloatToInt.OPCODE:
        return new FloatToInt(high, stream);
      case FloatToLong.OPCODE:
        return new FloatToLong(high, stream);
      case FloatToDouble.OPCODE:
        return new FloatToDouble(high, stream);
      case DoubleToInt.OPCODE:
        return new DoubleToInt(high, stream);
      case DoubleToLong.OPCODE:
        return new DoubleToLong(high, stream);
      case DoubleToFloat.OPCODE:
        return new DoubleToFloat(high, stream);
      case IntToByte.OPCODE:
        return new IntToByte(high, stream);
      case IntToChar.OPCODE:
        return new IntToChar(high, stream);
      case IntToShort.OPCODE:
        return new IntToShort(high, stream);
      case AddInt.OPCODE:
        return new AddInt(high, stream);
      case SubInt.OPCODE:
        return new SubInt(high, stream);
      case MulInt.OPCODE:
        return new MulInt(high, stream);
      case DivInt.OPCODE:
        return new DivInt(high, stream);
      case RemInt.OPCODE:
        return new RemInt(high, stream);
      case AndInt.OPCODE:
        return new AndInt(high, stream);
      case OrInt.OPCODE:
        return new OrInt(high, stream);
      case XorInt.OPCODE:
        return new XorInt(high, stream);
      case ShlInt.OPCODE:
        return new ShlInt(high, stream);
      case ShrInt.OPCODE:
        return new ShrInt(high, stream);
      case UshrInt.OPCODE:
        return new UshrInt(high, stream);
      case AddLong.OPCODE:
        return new AddLong(high, stream);
      case SubLong.OPCODE:
        return new SubLong(high, stream);
      case MulLong.OPCODE:
        return new MulLong(high, stream);
      case DivLong.OPCODE:
        return new DivLong(high, stream);
      case RemLong.OPCODE:
        return new RemLong(high, stream);
      case AndLong.OPCODE:
        return new AndLong(high, stream);
      case OrLong.OPCODE:
        return new OrLong(high, stream);
      case XorLong.OPCODE:
        return new XorLong(high, stream);
      case ShlLong.OPCODE:
        return new ShlLong(high, stream);
      case ShrLong.OPCODE:
        return new ShrLong(high, stream);
      case UshrLong.OPCODE:
        return new UshrLong(high, stream);
      case AddFloat.OPCODE:
        return new AddFloat(high, stream);
      case SubFloat.OPCODE:
        return new SubFloat(high, stream);
      case MulFloat.OPCODE:
        return new MulFloat(high, stream);
      case DivFloat.OPCODE:
        return new DivFloat(high, stream);
      case RemFloat.OPCODE:
        return new RemFloat(high, stream);
      case AddDouble.OPCODE:
        return new AddDouble(high, stream);
      case SubDouble.OPCODE:
        return new SubDouble(high, stream);
      case MulDouble.OPCODE:
        return new MulDouble(high, stream);
      case DivDouble.OPCODE:
        return new DivDouble(high, stream);
      case RemDouble.OPCODE:
        return new RemDouble(high, stream);
      case AddInt2Addr.OPCODE:
        return new AddInt2Addr(high, stream);
      case SubInt2Addr.OPCODE:
        return new SubInt2Addr(high, stream);
      case MulInt2Addr.OPCODE:
        return new MulInt2Addr(high, stream);
      case DivInt2Addr.OPCODE:
        return new DivInt2Addr(high, stream);
      case RemInt2Addr.OPCODE:
        return new RemInt2Addr(high, stream);
      case AndInt2Addr.OPCODE:
        return new AndInt2Addr(high, stream);
      case OrInt2Addr.OPCODE:
        return new OrInt2Addr(high, stream);
      case XorInt2Addr.OPCODE:
        return new XorInt2Addr(high, stream);
      case ShlInt2Addr.OPCODE:
        return new ShlInt2Addr(high, stream);
      case ShrInt2Addr.OPCODE:
        return new ShrInt2Addr(high, stream);
      case UshrInt2Addr.OPCODE:
        return new UshrInt2Addr(high, stream);
      case AddLong2Addr.OPCODE:
        return new AddLong2Addr(high, stream);
      case SubLong2Addr.OPCODE:
        return new SubLong2Addr(high, stream);
      case MulLong2Addr.OPCODE:
        return new MulLong2Addr(high, stream);
      case DivLong2Addr.OPCODE:
        return new DivLong2Addr(high, stream);
      case RemLong2Addr.OPCODE:
        return new RemLong2Addr(high, stream);
      case AndLong2Addr.OPCODE:
        return new AndLong2Addr(high, stream);
      case OrLong2Addr.OPCODE:
        return new OrLong2Addr(high, stream);
      case XorLong2Addr.OPCODE:
        return new XorLong2Addr(high, stream);
      case ShlLong2Addr.OPCODE:
        return new ShlLong2Addr(high, stream);
      case ShrLong2Addr.OPCODE:
        return new ShrLong2Addr(high, stream);
      case UshrLong2Addr.OPCODE:
        return new UshrLong2Addr(high, stream);
      case AddFloat2Addr.OPCODE:
        return new AddFloat2Addr(high, stream);
      case SubFloat2Addr.OPCODE:
        return new SubFloat2Addr(high, stream);
      case MulFloat2Addr.OPCODE:
        return new MulFloat2Addr(high, stream);
      case DivFloat2Addr.OPCODE:
        return new DivFloat2Addr(high, stream);
      case RemFloat2Addr.OPCODE:
        return new RemFloat2Addr(high, stream);
      case AddDouble2Addr.OPCODE:
        return new AddDouble2Addr(high, stream);
      case SubDouble2Addr.OPCODE:
        return new SubDouble2Addr(high, stream);
      case MulDouble2Addr.OPCODE:
        return new MulDouble2Addr(high, stream);
      case DivDouble2Addr.OPCODE:
        return new DivDouble2Addr(high, stream);
      case RemDouble2Addr.OPCODE:
        return new RemDouble2Addr(high, stream);
      case AddIntLit16.OPCODE:
        return new AddIntLit16(high, stream);
      case RsubInt.OPCODE:
        return new RsubInt(high, stream);
      case MulIntLit16.OPCODE:
        return new MulIntLit16(high, stream);
      case DivIntLit16.OPCODE:
        return new DivIntLit16(high, stream);
      case RemIntLit16.OPCODE:
        return new RemIntLit16(high, stream);
      case AndIntLit16.OPCODE:
        return new AndIntLit16(high, stream);
      case OrIntLit16.OPCODE:
        return new OrIntLit16(high, stream);
      case XorIntLit16.OPCODE:
        return new XorIntLit16(high, stream);
      case AddIntLit8.OPCODE:
        return new AddIntLit8(high, stream);
      case RsubIntLit8.OPCODE:
        return new RsubIntLit8(high, stream);
      case MulIntLit8.OPCODE:
        return new MulIntLit8(high, stream);
      case DivIntLit8.OPCODE:
        return new DivIntLit8(high, stream);
      case RemIntLit8.OPCODE:
        return new RemIntLit8(high, stream);
      case AndIntLit8.OPCODE:
        return new AndIntLit8(high, stream);
      case OrIntLit8.OPCODE:
        return new OrIntLit8(high, stream);
      case XorIntLit8.OPCODE:
        return new XorIntLit8(high, stream);
      case ShlIntLit8.OPCODE:
        return new ShlIntLit8(high, stream);
      case ShrIntLit8.OPCODE:
        return new ShrIntLit8(high, stream);
      case UshrIntLit8.OPCODE:
        return new UshrIntLit8(high, stream);
      case InvokePolymorphic.OPCODE:
        return new InvokePolymorphic(high, stream, mapping);
      case InvokePolymorphicRange.OPCODE:
        return new InvokePolymorphicRange(high, stream, mapping);
      case InvokeCustom.OPCODE:
        return new InvokeCustom(high, stream, mapping);
      case InvokeCustomRange.OPCODE:
        return new InvokeCustomRange(high, stream, mapping);
      case ConstMethodHandle.OPCODE:
        return new ConstMethodHandle(high, stream, mapping);
      case ConstMethodType.OPCODE:
        return new ConstMethodType(high, stream, mapping);
      default:
        throw new IllegalArgumentException("Illegal Opcode: 0x" + Integer.toString(opcode, 16));
    }
  }
}
