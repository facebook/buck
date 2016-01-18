package com.example.spock

import spock.lang.Specification

class PassingSpec extends Specification {
    def 'this very simple test passes'() {
    given:
        int value = 42
    when:
        String asString = Integer.toString(value)
    then:
        asString == "42"
    }
}
