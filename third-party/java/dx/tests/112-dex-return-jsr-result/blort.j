; Copyright (C) 2010 The Android Open Source Project
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

.method public static zorch(ZLjava/lang/Object;)Ljava/lang/Object;
    .limit locals 3
    .limit stack 1

    iload_0
    ifeq thenBlock
    jsr subroutine
    goto endBlock

thenBlock:
    jsr subroutine
    goto endBlock

subroutine:
    astore_2
    aload_1
    invokestatic java/lang/String/valueOf(Ljava/lang/Object;)Ljava/lang/String;
    astore_1
    ret 2

endBlock:
    aload_1
    areturn
.end method
