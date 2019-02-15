package com.example.friend_paths

import org.junit.runners.JUnit4
import org.junit.runner.RunWith
import org.junit.Test

@RunWith(JUnit4::class)
class PassingUnitTests {

    @Test
    fun testAbleToAccessInternalMethod() {
        Main().main()
    }
}
