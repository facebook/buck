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
    public static Object test1() {
        return ((Object[]) null)[0];
    }

    public static void test2() {
        ((Object[]) null)[0] = null;
    }

    public static int test3() {
        return ((Object[]) null).length;
    }

    public static Object test4() {
        Object[] arr = null;
        return arr[0];
    }

    public static void test5() {
        Object[] arr = null;
        arr[0] = null;
    }

    public static int test6() {
        Object[] arr = null;
        return arr.length;
    }

    public static Object test7(Object[] arr) {
        if (check()) {
            arr = null;
        }

        return arr[0];
    }

    public static void test8(Object[] arr) {
        if (check()) {
            arr = null;
        }

        arr[0] = null;
    }

    public static int test9(Object[] arr) {
        if (check()) {
            arr = null;
        }

        return arr.length;
    }

    public static boolean check() {
        return true;
    }
}
