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

.class Blort
.super java/lang/Object

; Methods to "consume" an int.
.method public static consume1(I)V
.limit stack 0
.limit locals 1
    nop
    return
.end method

.method public static consume2(I)V
.limit stack 0
.limit locals 1
    nop
    return
.end method

.method public static consume3(I)V
.limit stack 0
.limit locals 1
    nop
    return
.end method

.method public static consume4(I)V
.limit stack 0
.limit locals 1
    nop
    return
.end method

.method public static consume5(I)V
.limit stack 0
.limit locals 1
    nop
    return
.end method

.method public static consume6(I)V
.limit stack 0
.limit locals 1
    nop
    return
.end method

; Methods to "consume" a long.
.method public static consume1(J)V
.limit stack 0
.limit locals 2
    nop
    return
.end method

.method public static consume2(J)V
.limit stack 0
.limit locals 2
    nop
    return
.end method

.method public static consume3(J)V
.limit stack 0
.limit locals 2
    nop
    return
.end method

.method public static consume4(J)V
.limit stack 0
.limit locals 2
    nop
    return
.end method

; Test of "pop" opcode. This should end up causing a call to consume1(0).
.method public static test_pop()V
.limit stack 2
.limit locals 0
    iconst_0
    iconst_1
    pop          ; A1 -> (empty)
    invokestatic Blort/consume1(I)V
    return
.end method

; Test of "pop2" opcode, form 1. This should end up causing a call
; to consume1(0).
.method public static test_pop2_form1()V
.limit stack 3
.limit locals 0
    iconst_0
    iconst_1
    iconst_2
    pop2         ; A1 B1 -> (empty)
    invokestatic Blort/consume1(I)V
    return
.end method

; Test of "pop2" opcode, form 2. This should end up causing a call
; to consume1(0).
.method public static test_pop2_form2()V
.limit stack 3
.limit locals 0
    iconst_0
    lconst_0
    pop2         ; A2 -> (empty)
    invokestatic Blort/consume1(I)V
    return
.end method

; Test of "dup" opcode. This should end up causing these calls in order:
; consume1(0), consume2(0).
.method public static test_dup()V
.limit stack 2
.limit locals 0
    iconst_0
    dup          ; A1 -> A1 A1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(I)V
    return
.end method

; Test of "dup_x1" opcode. This should end up causing these calls in order:
; consume1(1), consume2(0), consume3(1).
.method public static test_dup_x1()V
.limit stack 3
.limit locals 0
    iconst_0
    iconst_1
    dup_x1       ; A1 B1 -> B1 A1 B1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(I)V
    invokestatic Blort/consume3(I)V
    return
.end method

; Test of "dup_x2" opcode, form 1. This should end up causing these calls
; in order: consume1(2), consume2(1), consume3(0), consume4(2).
.method public static test_dup_x2_form1()V
.limit stack 4
.limit locals 0
    iconst_0
    iconst_1
    iconst_2
    dup_x2       ; A1 B1 C1 -> C1 A1 B1 C1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(I)V
    invokestatic Blort/consume3(I)V
    invokestatic Blort/consume4(I)V
    return
.end method

; Test of "dup_x2" opcode, form 2. This should end up causing these calls
; in order: consume1(1), consume2(0L), consume3(1).
.method public static test_dup_x2_form2()V
.limit stack 4
.limit locals 0
    lconst_0
    iconst_1
    dup_x2       ; A2 B1 -> B1 A2 B1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(J)V
    invokestatic Blort/consume3(I)V
    return
.end method

; Test of "dup2" opcode, form 1. This should end up causing these calls
; in order: consume1(1), consume2(0), consume3(1), consume4(0).
.method public static test_dup2_form1()V
.limit stack 4
.limit locals 0
    iconst_0
    iconst_1
    dup2         ; A1 B1 -> A1 B1 A1 B1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(I)V
    invokestatic Blort/consume3(I)V
    invokestatic Blort/consume4(I)V
    return
.end method

; Test of "dup2" opcode, form 2. This should end up causing these calls
; in order: consume1(0L), consume2(0L).
.method public static test_dup2_form2()V
.limit stack 4
.limit locals 0
    lconst_0
    dup2         ; A2 -> A2 A2
    invokestatic Blort/consume1(J)V
    invokestatic Blort/consume2(J)V
    return
.end method

; Test of "dup2_x1" opcode, form 1. This should end up causing these calls
; in order: consume1(1), consume2(2), consume3(0), consume4(1), consume5(2).
.method public static test_dup2_x1_form1()V
.limit stack 5
.limit locals 0
    iconst_0
    iconst_1
    iconst_2
    dup2_x1      ; A1 B1 C1 -> B1 C1 A1 B1 C1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(I)V
    invokestatic Blort/consume3(I)V
    invokestatic Blort/consume4(I)V
    invokestatic Blort/consume5(I)V
    return
.end method


; Test of "dup2_x1" opcode, form 2. This should end up causing these calls
; in order: consume1(1L), consume2(2), consume3(1L).
.method public static test_dup2_x1_form2()V
.limit stack 5
.limit locals 0
    iconst_0
    lconst_1
    dup2_x1      ; A1 B2 -> B2 A1 B2
    invokestatic Blort/consume1(J)V
    invokestatic Blort/consume2(I)V
    invokestatic Blort/consume3(J)V
    return
.end method

; Test of "dup2_x2" opcode, form 1. This should end up causing these calls
; in order: consume1(3), consume2(2), consume3(1), consume4(0), consume5(3),
; consume6(2).
.method public static test_dup2_x2_form1()V
.limit stack 6
.limit locals 0
    iconst_0
    iconst_1
    iconst_2
    iconst_3
    dup2_x2      ; A1 B1 C1 D1 -> C1 D1 A1 B1 C1 D1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(I)V
    invokestatic Blort/consume3(I)V
    invokestatic Blort/consume4(I)V
    invokestatic Blort/consume5(I)V
    invokestatic Blort/consume6(I)V
    return
.end method

; Test of "dup2_x2" opcode, form 2. This should end up causing these calls
; in order: consume1(2L), consume2(1), consume3(0), consume4(2L).
.method public static test_dup2_x2_form2()V
.limit stack 6
.limit locals 0
    iconst_0
    iconst_1
    ldc2_w 2
    dup2_x2      ; A1 B1 C2 -> C2 A1 B1 C2
    invokestatic Blort/consume1(J)V
    invokestatic Blort/consume2(I)V
    invokestatic Blort/consume3(I)V
    invokestatic Blort/consume4(J)V
    return
.end method

; Test of "dup2_x2" opcode, form 3. This should end up causing these calls
; in order: consume1(2), consume2(1), consume3(0L), consume4(2), consume5(1).
.method public static test_dup2_x2_form3()V
.limit stack 6
.limit locals 0
    lconst_0
    iconst_1
    iconst_2
    dup2_x2      ; A2 B1 C1 -> B1 C1 A2 B1 C1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(I)V
    invokestatic Blort/consume3(J)V
    invokestatic Blort/consume4(I)V
    invokestatic Blort/consume5(I)V
    return
.end method

; Test of "dup2_x2" opcode, form 4. This should end up causing these calls
; in order: consume1(1L), consume2(0L), consume3(1L).
.method public static test_dup2_x2_form4()V
.limit stack 6
.limit locals 0
    lconst_0
    lconst_1
    dup2_x2      ; A2 B2 -> B2 A2 B2
    invokestatic Blort/consume1(J)V
    invokestatic Blort/consume2(J)V
    invokestatic Blort/consume3(J)V
    return
.end method

; Test of "swap" opcode. This should end up causing these calls
; in order: consume1(0), consume2(1).
.method public static test_swap()V
.limit stack 2
.limit locals 0
    iconst_0
    iconst_1
    swap         ; A1 B1 -> B1 A1
    invokestatic Blort/consume1(I)V
    invokestatic Blort/consume2(I)V
    return
.end method
