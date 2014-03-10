class Blort {

    // Test integers
    public static int testIntAddSub() {
        int a, b, c, d;
        a = 3;
        b = 5 - a;
        while (true) {
            c = a + b;
            d = 5;
            a = d - b;
            if (c <= d) {
                c = d + 1;
            } else {
                return c;
            }
            b = 2;
        }
    }

    public static int testIntMult() {
        int a = 6;
        int b = 9 - a;
        int c = b * 4;

        if (c > 10) {
            c = c - 10;
        }
        return c * 2;
    }

    public static int testIntDiv() {
        int a = 30;
        int b = 9 - a / 5;
        int c = b * 4;

        if (c > 10) {
            c = c - 10;
        }
        return c * (60 / a);
    }

    public static int testIntMod() {
        int a = 5;
        int b = a % 3;
        int c = a % 0;
        return b + c;
    }

    public static int testIntPhi() {
        int a = 37;
        int b = 3;
        int c = (b == 0) ? 0 : (a / b);
        return c;
    }

    // Test floats
    public static float testFloatAddSub() {
        float a, b, c, d;
        a = 3;
        b = 5 - a;
        while (true) {
            c = a + b;
            d = 5;
            a = d - b;
            if (c <= d) {
                c = d + 1;
            } else {
                return c;
            }
            b = 2;
        }
    }

    public static float testFloatMult() {
        float a = 6;
        float b = 9 - a;
        float c = b * 4;

        if (c > 10) {
            c = c - 10;
        }
        return c * 2;
    }

    public static float testFloatDiv() {
        float a = 30;
        float b = 9 - a / 5;
        float c = b * 4;

        if (c > 10) {
            c = c - 10;
        }
        return c * (60 / a);
    }

    public static float testFloatMod() {
        float a = 5;
        float b = a % 3;
        float c = a % 0;
        return b + c;
    }

    public static float testFloatPhi() {
        float a = 37;
        float b = 3;
        float c = (b == 0) ? 0 : (a / b);
        return c;
    }

    // Test doubles
    public static double testDoubleAddSub() {
        double a, b, c, d;
        a = 3;
        b = 5 - a;
        while (true) {
            c = a + b;
            d = 5;
            a = d - b;
            if (c <= d) {
                c = d + 1;
            } else {
                return c;
            }
            b = 2;
        }
    }

    public static double testDoubleMult() {
        double a = 6;
        double b = 9 - a;
        double c = b * 4;

        if (c > 10) {
            c = c - 10;
        }
        return c * 2;
    }

    public static double testDoubleDiv() {
        double a = 30;
        double b = 9 - a / 5;
        double c = b * 4;

        if (c > 10) {
            c = c - 10;
        }
        return c * (60 / a);
    }

    public static double testDoubleMod() {
        double a = 5;
        double b = a % 3;
        double c = a % 0;
        return b + c;
    }

    public static double testDoublePhi() {
        double a = 37;
        double b = 3;
        double c = (b == 0) ? 0 : (a / b);
        return c;
    }
}
