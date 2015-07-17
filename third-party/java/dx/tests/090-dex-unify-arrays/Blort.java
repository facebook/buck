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
    /*
     * Note: The use of the casts after the "?" in the following are
     * to avoid a bug in some (source code level) compilers.
     */

    static public Object test1(boolean b) {
        return (b ? new String[1] : new Integer[1])[0];
    }

    static public int test2(boolean b) {
        Object o = b ? (Object) new int[1] : new float[1];
        return o.hashCode();
    }

    static public int test3(boolean b) {
        Object o = b ? (Object) new char[1] : new double[1];
        return o.hashCode();
    }

    static public int test4(boolean b) {
        Object o = b ? (Object) new long[1] : new boolean[1];
        return o.hashCode();
    }

    static public int test5(boolean b) {
        Object o = b ? (Object) new short[1] : new Object[1];
        return o.hashCode();
    }

    static public int test6(boolean b) {
        Object o = b ? (Object) new byte[1] : new boolean[1];
        return o.hashCode();
    }

    static public Object test7(boolean b) {
        return (b ? new String[1] : new int[1][])[0];
    }

    static public Object[] test8(boolean b) {
        return (b ? new String[1][] : new int[1][][])[0];
    }
}
