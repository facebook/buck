package com.example.xcompile

class Bar {
    static String banana() {
        return "banana"
    }
}

class Quux extends Baz {
    public static void main(String[] args) {
        println (new Quux().banana())
    }
}
