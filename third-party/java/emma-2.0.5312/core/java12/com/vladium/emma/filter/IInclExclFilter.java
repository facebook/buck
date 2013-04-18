/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IInclExclFilter.java,v 1.1.1.1 2004/05/09 16:57:33 vlad_r Exp $
 */
package com.vladium.emma.filter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import com.vladium.util.WCMatcher;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, 2003
 */
public
interface IInclExclFilter
{
    // public: ................................................................
    
    // TODO: move this into util pkg
    
    char INCLUSION_PREFIX = '+';
    String INCLUSION_PREFIX_STRING = "+";
    char EXCLUSION_PREFIX = '-';
    String EXCLUSION_PREFIX_STRING = "-";

    boolean included (final String s);

    abstract class Factory
    {
        public static IInclExclFilter create (final String specs1, final String separators, final String [] specs2)
        {
            if ((specs1 == null) || (specs1.trim ().length () == 0))
                return create (specs2);
            else
            {
                final List /* String */ _specs = new ArrayList ();
                
                if (specs2 != null)
                {
                    for (int s = 0; s < specs2.length; ++ s)
                    {
                        _specs.add (specs2 [s]);
                    }
                }
                
                for (StringTokenizer tokenizer = new StringTokenizer (specs1, separators);
                     tokenizer.hasMoreTokens (); )
                {
                    _specs.add (tokenizer.nextToken ());
                }
                
                final String [] specs = new String [_specs.size ()];
                _specs.toArray (specs);
                
                return create (specs);
            }
        }
        
        public static IInclExclFilter create (final String [] specs)
        {
            if ((specs == null) || (specs.length == 0))
                return new WCInclExclFilter ((String []) null, (String []) null);
            
            final List inclusions = new ArrayList ();
            final List exclusions = new ArrayList ();
            
            for (int i = 0, iLimit = specs.length; i < iLimit; ++ i)
            {
                final String spec = specs [i];
                
                if (spec.length () > 0)
                {
                    if (spec.charAt (0) == EXCLUSION_PREFIX)
                        exclusions.add (spec.substring (1));
                    else
                    {
                        // [inclusion prefix is optional]
                        
                        if (spec.charAt (0) == INCLUSION_PREFIX)
                            inclusions.add (spec.substring (1));
                        else
                            inclusions.add (spec);
                    }
                } 
            }
            
            return new WCInclExclFilter (inclusions, exclusions);
        }
        
        public static IInclExclFilter create (final String [] inclusions,
                                              final String [] exclusions)
        {
            return new WCInclExclFilter (inclusions, exclusions);
        }
        
        public static IInclExclFilter create (final List /* String */ inclusions,
                                              final List /* String */ exclusions)
        {
            return new WCInclExclFilter (inclusions, exclusions);
        }

        private static final class WCInclExclFilter implements IInclExclFilter
        {
            public boolean included (final String s)
            {
                if (s == null) return false;
                
                final char [] chars = s.toCharArray ();
                
                // included set is (inclusions - exclusions), where null inclusions
                // mean 'everything' and null exclusions mean 'nothing':
                
                final WCMatcher [] inclusions = m_inclusions;
                final WCMatcher [] exclusions = m_exclusions;
                
                if (inclusions != null)
                {
                    boolean included = false;
                    
                    for (int i = 0, iLimit = inclusions.length; i < iLimit; ++ i)
                    {
                        if (inclusions [i].matches (chars))
                        {
                            included = true;
                            break;
                        }
                    }
                    
                    if (! included) return false;
                }
    
                if (exclusions != null)
                {
                    for (int x = 0, xLimit = exclusions.length; x < xLimit; ++ x)
                    {
                        if (exclusions [x].matches (chars)) return false;
                    }
                }
                
                return true;
            }
            
            
            WCInclExclFilter (final String [] inclusions,
                              final String [] exclusions)
            {
                if ((inclusions == null) || (inclusions.length == 0))
                    m_inclusions = null;
                else
                {
                    m_inclusions = new WCMatcher [inclusions.length];
                    
                    for (int i = 0; i < inclusions.length; ++ i)
                    {
                        m_inclusions [i] = WCMatcher.compile (inclusions [i]);
                    }
                }
                
                if ((exclusions == null) || (exclusions.length == 0))
                    m_exclusions = null;
                else
                {
                    m_exclusions = new WCMatcher [exclusions.length];
                    
                    for (int i = 0; i < exclusions.length; ++ i)
                    {
                        m_exclusions [i] = WCMatcher.compile (exclusions [i]);
                    }
                }
            }
            
            WCInclExclFilter (final List /* String */ inclusions,
                              final List /* String */ exclusions)
            {
                if ((inclusions == null) || inclusions.isEmpty ())
                    m_inclusions = null;
                else
                {
                    m_inclusions = new WCMatcher [inclusions.size ()];
                    
                    int ii = 0;
                    for (Iterator i = inclusions.iterator (); i.hasNext (); ++ ii)
                    {
                        final String pattern = (String) i.next ();
                        
                        m_inclusions [ii] = WCMatcher.compile (pattern);
                    }
                }
                
                if ((exclusions == null) || exclusions.isEmpty ())
                    m_exclusions = null;
                else
                {
                    m_exclusions = new WCMatcher [exclusions.size ()];
                    
                    int ii = 0;
                    for (Iterator i = exclusions.iterator (); i.hasNext (); ++ ii)
                    {
                        final String pattern = (String) i.next ();
                        
                        m_exclusions [ii] = WCMatcher.compile (pattern);
                    }
                }
            }
            
            
            private final WCMatcher [] m_inclusions, m_exclusions;
            
        } // end of nested class
        
    } // end of nested class

} // end of interface
// ----------------------------------------------------------------------------