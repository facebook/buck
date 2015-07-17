package testdata;

public class FillArrayData {

    public static byte[] newByteArray() {
        return new byte[] { 0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, -112, -23, 121 };
    }

    public static char[] newCharArray() {
        return new char[] { 0xFFFF, 0x4321, 0xABCD, 0, 'a', 'b', 'c' };
    }

    public static long[] newLongArray() {
        return new long[] { 4660046610375530309L, 7540113804746346429L, -6246583658587674878L };
    }
}
