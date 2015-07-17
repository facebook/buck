; Copyright (C) 2008 The Android Open Source Project
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

.method public static returnint()I
    .limit stack 1
    ldc 10
    ireturn
.end method

.method public static scalalocals()V
    .limit locals 5
    .limit stack 5
    .var 4 is x I from start to end
start:
    invokestatic blort/returnint()I
    invokestatic blort/returnint()I
    invokestatic blort/returnint()I
    invokestatic blort/returnint()I
    dup
    istore 4
    istore 2
    istore 3
    istore 1
    istore 0
    iload_2
    istore 4
    iload_3
end:
    return
.end method
