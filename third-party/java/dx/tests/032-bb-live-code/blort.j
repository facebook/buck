; Copyright (C) 2007 The Android Open Source Project
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;      http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

.class blort
.super java/lang/Object

.method public <init>()V
    .limit locals 1

    aload_0
    invokespecial java/lang/Object/<init>()V
    return
.end method

; Test that an exception handler for a live range is enlivened.
.method public test_live_exception([I)V
    nop
    nop
start:
    aload_0
    arraylength
end1:
    nop
end2:
    return
handler1:
    return
handler2:
    return
    .catch java/lang/RuntimeException from start to end2 using handler2
    .catch all from start to end1 using handler1
.end method

; Test that an exception handler for a live range is dead as long as
; the covered code can't possibly throw.
.method public test_dead_exception()V
    nop
    nop
start:
    nop
end1:
    nop
end2:
    return
handler1:
    return
handler2:
    return
    .catch java/lang/RuntimeException from start to end2 using handler2
    .catch all from start to end1 using handler1
.end method

; Test all the if* variants.
.method public test_ifs()V
    ifeq x0
    ifne x1
    iflt x2
    ifge x3
    ifgt x4
    ifle x5
    if_icmpeq x6
    if_icmpne x7
    if_icmplt x8
    if_icmpge x9
    if_icmpgt x10
    if_icmple x11
    if_acmpeq x12
    if_acmpne x13
    ifnull x14
    ifnonnull x15
    return
x0:
    return
x1:
    return
x2:
    return
x3:
    return
x4:
    return
x5:
    return
x6:
    return
x7:
    return
x8:
    return
x9:
    return
x10:
    return
x11:
    return
x12:
    return
x13:
    return
x14:
    return
x15:
    return
.end method

; Test jsr and jsr_w.
.method public test_jsr()V
    jsr j1
    jsr_w j2
    return
j1:
    astore_0
    ret 0
j2:
    astore_0
    ret_w 0
.end method

; Test tableswitch.
.method public test_tableswitch()V
    tableswitch 0x10
        t1
        t2
        default: t3
t1:
    return
t2:
    return
t3:
    return
.end method

; Test lookupswitch.
.method public test_lookupswitch()V
    lookupswitch
        0x05: s1
        0x10: s2
        default: s3
s1:
    return
s2:
    return
s3:
    return
.end method

; Test every non-branching op.
.method public test_nonbranch()V
    nop
    aconst_null
    iconst_m1
    iconst_0
    iconst_1
    iconst_2
    iconst_3
    iconst_4
    iconst_5
    lconst_0
    lconst_1
    fconst_0
    fconst_1
    fconst_2
    dconst_0
    dconst_1
    bipush 0x10
    sipush 0x1000
    ldc "x"
    ldc_w "y"
    ldc2_w 3.0
    iload 5
    lload 5
    fload 5
    dload 5
    aload 5
    iload_0
    iload_1
    iload_2
    iload_3
    lload_0
    lload_1
    lload_2
    lload_3
    fload_0
    fload_1
    fload_2
    fload_3
    dload_0
    dload_1
    dload_2
    dload_3
    aload_0
    aload_1
    aload_2
    aload_3
    iaload
    laload
    faload
    daload
    aaload
    baload
    caload
    saload
    istore 5
    lstore 5
    fstore 5
    dstore 5
    astore 5
    istore_0
    istore_1
    istore_2
    istore_3
    lstore_0
    lstore_1
    lstore_2
    lstore_3
    fstore_0
    fstore_1
    fstore_2
    fstore_3
    dstore_0
    dstore_1
    dstore_2
    dstore_3
    astore_0
    astore_1
    astore_2
    astore_3
    iastore
    lastore
    fastore
    dastore
    aastore
    bastore
    castore
    sastore
    pop
    pop2
    dup
    dup_x1
    dup_x2
    dup2
    dup2_x1
    dup2_x2
    swap
    iadd
    ladd
    fadd
    dadd
    isub
    lsub
    fsub
    dsub
    imul
    lmul
    fmul
    dmul
    idiv
    ldiv
    fdiv
    ddiv
    irem
    lrem
    frem
    drem
    ineg
    lneg
    fneg
    dneg
    ishl
    lshl
    ishr
    lshr
    iushr
    lushr
    iand
    land
    ior
    lor
    ixor
    lxor
    iinc 5 0x10
    i2l
    i2f
    i2d
    l2i
    l2f
    l2d
    f2i
    f2l
    f2d
    d2i
    d2l
    d2f
    i2b
    i2c
    i2s
    lcmp
    fcmpl
    fcmpg
    dcmpl
    dcmpg
    getstatic blort/x I
    putstatic blort/x I
    getfield blort/x I
    putfield blort/x I
    invokevirtual blort/x()V
    invokespecial blort/x()V
    invokestatic blort/x()V
    invokeinterface blort/x()V 1
    new blort
    newarray int
    anewarray blort
    arraylength
    checkcast blort
    instanceof blort
    monitorenter
    monitorexit
    iload 0x100
    lload 0x100
    fload 0x100
    dload 0x100
    aload 0x100
    istore 0x100
    lstore 0x100
    fstore 0x100
    dstore 0x100
    astore 0x100
    iinc 0x123 0x321
    multianewarray [[[I 2
    return
.end method
