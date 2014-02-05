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

; dead code after the last reachable instruction in a method
.method public test_deadend1()V
    return
    aload_0
.end method

; dead code after the last reachable instruction in a method
.method public test_deadend2()V
    ireturn
    aload_0
    aload_0
.end method

; dead code after the last reachable instruction in a method
.method public test_deadend3()V
    aconst_null
    athrow
    sipush 0x1234
.end method

; make sure an exception handler for a dead range doesn't get enlivened
.method public test_dead_exception_handler()V
    return
    nop
blort:
    nop
    nop
    return
handler:
    nop
    return
    .catch all from blort to handler using handler
.end method

; dead code after goto instruction
.method public test_dead_goto()V
    goto blort
    nop
blort:
    return
.end method

; dead code after ret instruction
.method public test_dead_ret()V
    ifeq blort
    ret 0
    iconst_m1
blort:
    return
.end method

; dead code after tableswitch instruction
.method public test_dead_tableswitch()V
    tableswitch 0x10
        blort
        default: blort
    nop
    nop
    nop
    aload_0
    aload_1
    aload_2
    aload_3
blort:
    return
.end method

; dead code after lookupswitch instruction
.method public test_dead_lookupswitch()V
    lookupswitch
        0x10: blort
        0x20: blort
        default: blort
    ldc "WHYA REYO UREA DING THIS ?"
blort:
    return
.end method

; dead code after ireturn instruction
.method public test_dead_ireturn()V
    ifeq blort
    ireturn
    iconst_1
blort:
    return
.end method

; dead code after lreturn instruction
.method public test_dead_lreturn()V
    ifeq blort
    lreturn
    iconst_1
blort:
    return
.end method

; dead code after freturn instruction
.method public test_dead_freturn()V
    ifeq blort
    freturn
    iconst_1
blort:
    return
.end method

; dead code after dreturn instruction
.method public test_dead_dreturn()V
    ifeq blort
    dreturn
    iconst_1
blort:
    return
.end method

; dead code after areturn instruction
.method public test_dead_areturn()V
    ifeq blort
    areturn
    iconst_1
blort:
    return
.end method

; dead code after return instruction
.method public test_dead_return()V
    ifeq blort
    return
    iconst_1
blort:
    return
.end method

; dead code after athrow instruction
.method public test_dead_athrow()V
    ifeq blort
    athrow
    iconst_1
blort:
    return
.end method

; dead code after wide ret instruction
.method public test_dead_wideret()V
    ifeq blort
    ret 0x0100
    iconst_1
blort:
    return
.end method

; dead code after goto_w instruction
.method public test_dead_goto_w()V
    goto_w blort
    iconst_1
blort:
    return
.end method
