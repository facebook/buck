using System;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace UnitTestProject1
{
    [TestClass]
    public class FailedTest
    {
        [TestMethod]
        public void passes()
        {
            Assert.AreEqual("foo", "foo");
        }

        [TestMethod]
        public void fails()
        {
            Assert.AreEqual("foo", "bar");
        }
    }
}
