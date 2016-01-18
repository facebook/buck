package com.example.spock

import spock.lang.Specification

class FailingSpec extends Specification {
    def 'this very simple test fails'() {
        given:
        int value = 42
        when:
        String asString = Integer.toString(value)
        then:
        asString == "43"
    }
}
