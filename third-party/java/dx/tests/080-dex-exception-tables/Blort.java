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
    public static void call1() { }
    public static void call2() { }
    public static void call3() { }
    public static void call4() { }
    public static void call5() { }

    public static int test1() {
        try {
            call1();
            call2();
        } catch (IndexOutOfBoundsException ex) {
            return 10;
        } catch (RuntimeException ex) {
            return 11;
        }

        call3();
        return 12;
    }

    public static int test2() {
        try {
            call1();
            try {
                call2();
            } catch (IndexOutOfBoundsException ex) {
                return 10;
            }
            call3();
        } catch (RuntimeException ex) {
            return 11;
        }

        return 12;
    }

    public static int test3() {
        try {
            call1();
            try {
                call2();
                try {
                    call3();
                } catch (NullPointerException ex) {
                    return 10;
                }
                call4();
            } catch (IndexOutOfBoundsException ex) {
                return 11;
            }
            call5();
        } catch (RuntimeException ex) {
            return 12;
        }

        return 13;
    }

    public static int test4() {
        try {
            call1();
            try {
                call2();
                try {
                    call3();
                } catch (NullPointerException ex) {
                    return 10;
                }
            } catch (IndexOutOfBoundsException ex) {
                return 11;
            }
            call5();
        } catch (RuntimeException ex) {
            return 12;
        }

        return 13;
    }

    public static int test5() {
        try {
            call1();
            try {
                call2();
                try {
                    call3();
                } catch (NullPointerException ex) {
                    return 10;
                }
            } catch (IndexOutOfBoundsException ex) {
                return 11;
            }
        } catch (RuntimeException ex) {
            return 12;
        }

        return 13;
    }

    public static int test6() {
        try {
            try {
                try {
                    call1();
                } catch (NullPointerException ex) {
                    return 10;
                }
                call2();
            } catch (IndexOutOfBoundsException ex) {
                return 11;
            }
            call3();
        } catch (RuntimeException ex) {
            return 12;
        }

        call4();
        return 13;
    }

    public static int test7() {
        try {
            call1();
        } catch (RuntimeException ex) {
            return 10;
        }

        try {
            call2();
        } catch (RuntimeException ex) {
            return 11;
        }

        return 12;
    }

    public static int test8() {
        try {
            call1();
            call2();
        } catch (RuntimeException ex) {
            return 10;
        }

        try {
            call3();
            call4();
        } catch (RuntimeException ex) {
            return 11;
        }

        return 12;
    }

    public static int test9() {
        try {
            call1();
            try {
                call2();
            } catch (IllegalArgumentException ex) {
                return 10;
            }
        } catch (RuntimeException ex) {
            return 11;
        }

        try {
            call3();
            try {
                call4();
            } catch (IllegalArgumentException ex) {
                return 12;
            }
        } catch (RuntimeException ex) {
            return 13;
        }

        return 14;
    }

}
