/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IElement.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IElement extends ISimpleElement, IElementList
{
    // public: ................................................................
    
    IElement setText (String text, boolean nbsp); // size() is 0 after this
    
    abstract class Factory
    {
        public static IElement create (final Tag tag)
        {
            return new ElementImpl (tag, AttributeSet.create ());
        }
        
        public static IElement create (final Tag tag, final AttributeSet attrs)
        {
            return new ElementImpl (tag, attrs);
        }
        
        // TODO: should this extend ElementList?
        static class ElementImpl extends ISimpleElement.Factory.SimpleElementImpl
                                         implements IElement
        {
            public String toString ()
            {
                return "<" + m_tag.getName () + ">";
            }
            
            public void emit (final HTMLWriter out)
            {
                final String tagName = m_tag.getName ();
                
                out.write ('<');
                out.write (tagName);
                
                if (! m_attrs.isEmpty ())
                {
                    out.write (' ');
                    m_attrs.emit (out);
                }
                
                out.write ('>');
                
                for (Iterator c = m_contents.iterator (); c.hasNext (); )
                {
                    final IContent content = (IContent) c.next ();
                    content.emit (out);
                }
                
                out.write ("</");
                out.write (tagName);
                out.write ('>');
                if (DEBUG_HTML) out.eol (); // using ENABLED as DEBUG here
            }
                        
            public IElementList add (final IContent content)
            {
                if (content != null)
                {
                    m_contents.add (content);
                }
                
                return this;
            }
            
            public IElementList add (final int index, final IContent content)
            {
                if (content != null)
                {
                    m_contents.add (index, content);
                }
                
                return this;
            }
            
            public int size ()
            {
                return m_contents.size ();
            }
            
            public IElement setText (final String text, final boolean nbsp)
            {
                if (text != null)
                {
                    m_contents.clear ();
                    m_contents.add (new Text (text, nbsp));
                }
                
                return this;
            }
            
            ElementImpl (final Tag tag, final AttributeSet attrs)
            {
                super (tag, attrs);
                
                m_contents = new ArrayList ();
            }

            
            protected final List /* Content */ m_contents;
            
            private static final boolean DEBUG_HTML = false;
                    
        } // end of nested class

    } // end of nested class 
    
} // end of interface
// ----------------------------------------------------------------------------