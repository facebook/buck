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

; Test jsr and jsr_w.
.method public test_jsr()Ljava/lang/Object;
    .limit locals 3
    .limit stack 4
    aload_0
    jsr j1
    aload_0
    pop
    ; Call j1 with different locals
    ldc 10
    istore_0
    jsr j1
    iload_0
    pop
    jsr j3
    areturn
j1:
    astore_2
    jsr_w j2
    ret 2
j2:
    ; a subroutine with two returns and a catch block
    astore_1
    dup
    dup
    ; Just something that could throw an exception...
    invokevirtual blort.test_jsr()V
    ifnonnull j2a
    ret_w 1
j2a:
    ret_w 1
j3:
    ; a subroutine that does not return
    pop
    areturn
catchBlock:
    areturn

.catch java/lang/Throwable from j2 to j2a using catchBlock
.end method
