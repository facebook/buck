package com.example.basic

import org.junit.Assert.assertNotNull
import org.junit.runners.JUnit4
import org.junit.runner.RunWith
import org.junit.Test

@RunWith(JUnit4::class)
class PassingUnitTests {
    @Test
    fun testBasicAssertion() {
        val str = "I am not null."
        assertNotNull(str)
    }

    @Test
    fun testMultiplication() {
        val num1 = 4
        val num2 = 6
        assert(num1 * num2 == 24)
    }
}
