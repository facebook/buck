/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class Blort
{
    public static void caught() {
        // This space intentionally left blank.
    }

    public static void zorch(int x) {
        // This space intentionally left blank.
    }

    public static void test1(int x) {
        // In this test, the code being try-caught can't possibly throw.
        try {
            x = 0;
        } catch (RuntimeException ex) {
            caught();
        }
    }

    public static void test2(String[] sa) {
        // In this test, the code being try-caught doesn't contain any
        // constant pool references.
        try {
            int x = sa.length;
        } catch (RuntimeException ex) {
            caught();
        }
    }

    public static void test3() {
        // In this test, the code being try-caught contains a constant
        // pool reference.
        try {
            zorch(1);
        } catch (RuntimeException ex) {
            caught();
        }
    }

    public static void test4(String[] sa) {
        // In this test, the code being try-caught contains one
        // throwing instruction that has a constant pool reference and
        // one that doesn't.
        try {
            zorch(sa.length);
        } catch (RuntimeException ex) {
            caught();
        }
    }
}
