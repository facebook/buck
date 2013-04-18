/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: WCMatcher.java,v 1.1.1.1 2004/05/09 16:57:56 vlad_r Exp $
 */
package com.vladium.util;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2002
 */
public
abstract class WCMatcher
{
    // public: ................................................................
    

    public static WCMatcher compile (final String pattern)
    {
        if (pattern == null) throw new IllegalArgumentException ("null input: pattern");
        
        final char [] chars = pattern.toCharArray (); // is this faster than using charAt()?
        final int charsLength = chars.length;
        
        if (charsLength == 0)
            return EMPTY_MATCHER; // TODO: should be an EMPTY_MATCHER
        else
        {
            int patternLength = 0, starCount = 0, questionCount = 0;
            boolean star = false;
            
            for (int c = 0; c < charsLength; ++ c)
            {
                final char ch = chars [c];
                if (ch == '*')
                {
                    if (! star)
                    {
                        star = true;
                        ++ starCount; 
                        chars [patternLength ++] = '*';
                    }
                }
                else
                {
                    star = false;
                    if (ch == '?') ++ questionCount;
                    chars [patternLength ++] = ch;
                }
            }
            
            // [assertion: patternLength > 0]
            
            if ((starCount == 1) && (questionCount == 0))
            {
                if (patternLength == 1)
                    return ALL_MATCHER;
                else if (chars [0] == '*')
                    return new EndsWithMatcher (chars, patternLength);
                else if (chars [patternLength - 1] == '*')
                    return new StartsWithMatcher (chars, patternLength);
            }
            
            return new PatternMatcher (chars, patternLength);
        }
    }
    
    public abstract boolean matches (String s);
    public abstract boolean matches (char [] chars);

        
//    private boolean matches (int pi, int si, final char [] string)
//    {
//        System.out.println ("pi = " + pi + ", si = " + si);
//        
//        if (pi == m_pattern.length)
//            return si == string.length;
//        else
//        {
//            switch (m_pattern [pi])
//            {
//                case '?':
//                {
//                    return (si < string.length) && matches (pi + 1, si + 1, string);
//                }
//                
//                case '*':
//                {
//                    return matches (pi + 1, si, string) || ((si < string.length) && matches (pi, si + 1, string));
//                }
//                
//                default:
//                {
//                    return (si < string.length) && (m_pattern [pi] == string [si]) && matches (pi + 1, si + 1, string);
//                }
//                
//            } // end of switch
//        }
//    }
    

        
    // protected: .............................................................
    
    // package: ...............................................................


    WCMatcher () {}
    
    // private: ...............................................................
    
    
    private static final class AllMatcher extends WCMatcher
    {
        public final boolean matches (final String s)
        {
            if (s == null) throw new IllegalArgumentException  ("null input: s");
            
            return true;
        }
        
        public final boolean matches (final char [] chars)
        {
            if (chars == null) throw new IllegalArgumentException  ("null input: chars");
            
            return true;
        }
        
    } // end of nested class
    

    private static final class EmptyMatcher extends WCMatcher
    {
        public final boolean matches (final String s)
        {
            if (s == null) throw new IllegalArgumentException  ("null input: s");
            
            return false;
        }
        
        public final boolean matches (final char [] chars)
        {
            if (chars == null) throw new IllegalArgumentException  ("null input: chars");
            
            return chars.length == 0;
        }
        
    } // end of nested class
    
    
    private static final class StartsWithMatcher extends WCMatcher
    {
        public final boolean matches (final String s)
        {
            if (s == null) throw new IllegalArgumentException  ("null input: s");
            
            return s.startsWith (m_prefix);
        }
        
        public final boolean matches (final char [] chars)
        {
            if (chars == null) throw new IllegalArgumentException  ("null input: chars");
            
            final char [] prefixChars = m_prefixChars;
            final int prefixLength = prefixChars.length - 1;
            
            if (chars.length < prefixLength) return false;
            
            for (int c = 0; c < prefixLength; ++ c)
            {
                if (chars [c] != prefixChars [c]) return false; 
            }
            
            return true;
        }
        
        StartsWithMatcher (final char [] pattern, final int patternLength)
        {
            m_prefixChars = pattern;            
            m_prefix = new String (pattern, 0, patternLength - 1);
        }
        
        private final char [] m_prefixChars;
        private final String m_prefix;
        
    } // end of nested class
    
    
    private static final class EndsWithMatcher extends WCMatcher
    {
        public final boolean matches (final String s)
        {
            if (s == null) throw new IllegalArgumentException  ("null input: s");
            
            return s.endsWith (m_suffix);
        }
        
        public final boolean matches (final char [] chars)
        {
            if (chars == null) throw new IllegalArgumentException  ("null input: chars");
            
            final char [] suffixChars = m_suffixChars;
            final int suffixLength = suffixChars.length - 1;
            final int charsLength = chars.length;
            
            if (charsLength < suffixLength) return false;
            
            for (int c = 0; c < suffixLength; ++ c)
            {
                if (chars [charsLength - 1 - c] != suffixChars [suffixLength - c]) return false; 
            }
            
            return true;
        }
        
        EndsWithMatcher (final char [] pattern, final int patternLength)
        {
            m_suffixChars = pattern;
            m_suffix = new String (pattern, 1, patternLength - 1);
        }
        
        private final char [] m_suffixChars;
        private final String m_suffix;
        
    } // end of nested class
    

    private static final class PatternMatcher extends WCMatcher
    {
        public final boolean matches (final String s)
        {
            if (s == null) throw new IllegalArgumentException  ("null input: s");

            final char [] string = s.toCharArray (); // implies an array copy; is this faster than using charAt()?
            final int stringLength = string.length;

            final char [] pattern = m_pattern;
            final int patternLength = m_patternLength;
            
            // [assertion: patternLength > 0]
            
            int si = 0, pi = 0;
            boolean star = false;
            
            
      next: while (true)
            {
                //System.out.println ("pi = " + pi + ", si = " + si);
                
                int i = 0;
                for ( ; pi + i < patternLength; ++ i)
                {
                    final char patternChar = pattern [pi + i];
                     
                    if (patternChar == '*')
                    {
                        si += i;
                        pi += (i + 1);
                        
                        star = true;
                        continue next;
                    }
                    
                    final int si_i = si + i;
                     
                    if (si_i == stringLength) return false;
                    
                    if (patternChar != string [si_i])
                    {
                        if (patternChar == '?') continue;
                        
                        if (! star) return false;
                        ++ si;
                        
                        continue next;
                    }
                    
                } // end of for
                
                if (si + i == stringLength) return true;
                
                if (! star) return false;
                ++ si;
                
                // [continue next;]
            }
        }
        
        
        public final boolean matches (final char [] string)
        {
            if (string == null) throw new IllegalArgumentException  ("null input: string");

            final int stringLength = string.length;

            final char [] pattern = m_pattern;
            final int patternLength = m_patternLength;
            
            // [assertion: patternLength > 0]
            
            int si = 0, pi = 0;
            boolean star = false;
            
            
      next: while (true)
            {
                //System.out.println ("pi = " + pi + ", si = " + si);
                
                int i = 0;
                for ( ; pi + i < patternLength; ++ i)
                {
                    final char patternChar = pattern [pi + i];
                     
                    if (patternChar == '*')
                    {
                        si += i;
                        pi += (i + 1);
                        
                        star = true;
                        continue next;
                    }
                    
                    final int si_i = si + i;
                     
                    if (si_i == stringLength) return false;
                    
                    if (patternChar != string [si_i])
                    {
                        if (patternChar == '?') continue;
                        
                        if (! star) return false;
                        ++ si;
                        
                        continue next;
                    }
                    
                } // end of for
                
                if (si + i == stringLength) return true;
                
                if (! star) return false;
                ++ si;
                
                // [continue next;]
            }
        }
        
        PatternMatcher (final char [] pattern, final int patternLength)
        {
            m_pattern = pattern;
            m_patternLength = patternLength;
        }
        
        
        private final char [] m_pattern;
        private final int m_patternLength;
        
    } // end of nested class

        
    private static final WCMatcher ALL_MATCHER = new AllMatcher ();
    private static final WCMatcher EMPTY_MATCHER = new EmptyMatcher ();

} // end of class
// ----------------------------------------------------------------------------