
class Blort {

    static void methodThatNeedsInvokeRange
        (int a, int b, int c, int d, int e, int f) {
    }

    void testNoLocals() {
        methodThatNeedsInvokeRange(5, 0, 5, 0, 5, 0);
    }

    void testMixedLocals() {
        int src = 6;
        int dest = 7;

        methodThatNeedsInvokeRange(src, 0, dest, 1, 5, 0);
        methodThatNeedsInvokeRange(src, 0, dest, 1, 5, 0);
    }

    // here the current algorithm partial-overlapping will stumble a bit
    // The register containing "zero" will be marked as "reserved for locals"
    // Then the subsequent arraycopy will need a whole new set of 5 registers
    void testMixedWorseCase() {
        int src = 6;
        int dest = 7;
        int zero = 0;

        methodThatNeedsInvokeRange(src, zero, dest, 1, 5, 0);
        methodThatNeedsInvokeRange(src, 0, dest, 1, 5, 0);
    }

    void testAllParams(int a, int b, int c, int d, int e, int f) {
        methodThatNeedsInvokeRange(a, b, c, d, e, f);
    }

    // this could try to make use of param positions, but doesn't
    static void testTailParams(int destPos, int length) {
        int src = 6;
        int dest = 7;

        methodThatNeedsInvokeRange(src, 0, dest, 0, destPos, length);
    }


    // This presently requires a whole N new registers
    void testFlip() {
        int src = 6;
        int dest = 7;

        methodThatNeedsInvokeRange(src, 0, dest, 1, 5, 0);
        methodThatNeedsInvokeRange(dest, 0, src, 1, 5, 0);
    }

    // ensure that an attempt to combine registers for a local
    // with a differing category doesn't mess us up.
    long testMixedCategory(boolean foo) {
        if (foo) {
            int offset = 1;
            int src = 6;
            int dest = 7;

            methodThatNeedsInvokeRange(src, 0, dest, offset, 5, 0);
            return offset;
        } else {
            long offset = System.currentTimeMillis();;
            return offset;
        }
    }
}
