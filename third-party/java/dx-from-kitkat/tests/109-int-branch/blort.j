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

.class blort
.super java/lang/Object

.method public static test1(ZBCSI[I)V
    .limit locals 6
    .limit stack 3

    iload_0
    iload_1
    if_icmpeq zorch

    iload_2
    iload_3
    if_icmpne zorch

    iload 4
    aload 5
    iconst_0
    iaload
    if_icmplt zorch

    aload 5
    iconst_0
    iaload
    iload_0
    if_icmpgt zorch

    iload 4
    iload_1
    if_icmpge zorch

    nop

zorch:
    return
.end method

.method public static test2(I)Ljava/lang/Object;
    .limit locals 2
    .limit stack 3

    aconst_null
    astore 1

    aload_1
    iconst_0
    iaload
    iload_0
    if_icmpge zorch

    nop

zorch:
    aconst_null
    areturn
.end method

.method public static test3(I[I)Ljava/lang/Object;
    .limit locals 3
    .limit stack 3

    aconst_null
    astore 2

frotz:
    aload_2
    ifnonnull fizmo

    aload_1
    astore_2
    goto frotz

fizmo:
    aload_2
    iconst_0
    iaload
    iload_0
    if_icmpge zorch

    nop

zorch:
    aconst_null
    areturn
.end method
