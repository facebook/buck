package com.example.basic

import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.runners.JUnit4
import org.junit.runner.RunWith
import org.junit.Test

@RunWith(JUnit4::class)
class FailingUnitTest {

    @Test
    fun testBasicAssertion() {
        val str = "I am not null."
        assertNotNull(str)
    }

    @Test
    fun testOneFailing() {
        assertTrue("2 does not equal 4", 2 == 4)
    }

    @Test
    fun testOnePassing() {
        assertTrue("null is equal to null", null == null)
    }
}
