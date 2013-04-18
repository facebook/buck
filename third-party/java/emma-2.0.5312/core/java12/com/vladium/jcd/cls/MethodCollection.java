/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: MethodCollection.java,v 1.1.1.1 2004/05/09 16:57:47 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.vladium.jcd.lib.UDataOutputStream;
import com.vladium.util.IntVector;

// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vlad Roubtsov
 */
final class MethodCollection implements IMethodCollection
{
    // public: ................................................................

    // ACCESSORS:
        
    public Method_info get (final int offset)
    {
        return (Method_info) m_methods.get (offset);
    }
    
    public int [] get (final ClassDef cls, final String name)
    {
        if (cls == null) throw new IllegalArgumentException  ("null input: cls");
        
        // TODO: hash impl [not possible without having access to the parent ClassDef]
        
        final int count = m_methods.size (); // use size() if class becomes non-final
        final IntVector result = new IntVector (count);

        for (int m = 0; m < count; ++ m)
        {
            final Method_info method = (Method_info) m_methods.get (m);
            
            if (method.getName (cls).equals (name))  
                result.add (m);
        }
        
        return result.values (); // IntVector optimizes for the empty case 
    }
    
    public int size ()
    {
        return m_methods.size ();
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        try
        {
            final MethodCollection _clone = (MethodCollection) super.clone ();
            
            // deep clone:
            final int methods_count = m_methods.size (); // use size() if class becomes non-final
            _clone.m_methods = new ArrayList (methods_count);
            for (int m = 0; m < methods_count; ++ m)
            {
                _clone.m_methods.add (((Method_info) m_methods.get (m)).clone ());
            }
            
            return _clone;
        }
        catch (CloneNotSupportedException e)
        {
            throw new InternalError (e.toString ());
        }        
    }
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        final int methods_count = m_methods.size (); // use size() if class becomes non-final
        out.writeU2 (methods_count);
        
        for (int i = 0; i < methods_count; i++)
        {
            get (i).writeInClassFormat (out);
        }
    }
    
    // Visitor:
    
    public void accept (final IClassDefVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    

    // MUTATORS:

    public int add (final Method_info method)
    {
        final int newoffset = m_methods.size ();  // use size() if class becomes non-final
        m_methods.add (method);
        
        return newoffset;
    }
    
    public Method_info set (final int offset, final Method_info method)
    {
        return (Method_info) m_methods.set (offset, method);
    }
    
    public Method_info remove (final int offset)
    {
        return (Method_info) m_methods.remove (offset);
    }
    
    // protected: .............................................................

    // package: ...............................................................


    MethodCollection (final int capacity)
    {
        m_methods = capacity < 0 ? new ArrayList () : new ArrayList (capacity);
    }

    // private: ...............................................................

    
    private List/* Method_info */ m_methods; // method collection

} // end of class
// ----------------------------------------------------------------------------

