using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace ConsoleApp1
{
    public class Lib2
    {
        private string replacement;

        public Lib2(String replacement)
        {
            this.replacement = replacement;
        }

        public String format(String formatString)
        {
            return String.Format("Lib2.format: {0}", String.Format(formatString, this.replacement));
        }
    }
}
