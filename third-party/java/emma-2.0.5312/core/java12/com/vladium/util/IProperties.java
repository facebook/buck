/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IProperties.java,v 1.1.1.1.2.1 2004/07/08 10:52:10 vlad_r Exp $
 */
package com.vladium.util;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IProperties
{
    // public: ................................................................
    
    /**
     * An IMapper is a stateless hook for mapping a arbitrary property key
     * to another (useful, for example, for property aliasing and defaulting).
     * Each IMapper must be completely stateless and could be shared between
     * multiple IProperties instances (and invoked from multiple concurrent threads).
     */
    interface IMapper
    {
        String getMappedKey (final String key);
        
    } // end of nested interface
    
    
    String getProperty (String key);
    String getProperty (String key, String dflt);
    boolean isOverridden (String key);
    
    IProperties copy ();
    Iterator /* String */ properties ();
    Properties toProperties ();
    /**
     * @param prefix [may not be null]
     */
    String [] toAppArgsForm (final String prefix);
    boolean isEmpty ();
    void list (PrintStream out);
    void list (PrintWriter out);

    String setProperty (String key, String value);
    
    
    abstract class Factory
    {
        /**
         * Creates an empty IProperties set with an optional property mapper.
         * 
         * @param mapper [may be null]
         * @return an empty property set [never null]
         */
        public static IProperties create (final IMapper mapper)
        {
            return new PropertiesImpl (null, mapper);
        }
        
        /**
         * Converts a java.util.Properties instance to an IProperties instance,
         * with an optional property mapper. Note that 'properties' content is
         * shallowly cloned, so the result can be mutated independently from
         * the input.
         * 
         * @param properties [may not be null]
         * @param mapper [may be null]
         * @return a property set based on 'properties' [never null]
         */
        public static IProperties wrap (final Properties properties, final IMapper mapper)
        {
            final HashMap map = new HashMap ();
            
            // always use propertyNames() for traversing java.util.Properties:
            
            for (Enumeration names = properties.propertyNames (); names.hasMoreElements (); )
            {
                final String n = (String) names.nextElement ();
                final String v = properties.getProperty (n);
                
                map.put (n, v);
            }
            
            return new PropertiesImpl (map, mapper); // note: map is a defensive clone
        }

        /**
         * Combines two property sets by creating a property set that chains 'overrides'
         * to 'base' for property delegation. Note that 'overrides' are cloned
         * defensively and so the result can be mutated independently of both inputs.
         * 
         * @param overrides [may be null]
         * @param base [may be null]
         * @return [never null; an empty property set with a null mapper is created
         * if both inputs are null]
         */
        public static IProperties combine (final IProperties overrides, final IProperties base)
        {
            final IProperties result = overrides != null
                ? overrides.copy ()
                : create (null);
            
            // [assertion: result != null]

            ((PropertiesImpl) result).getLastProperties ().setDelegate ((PropertiesImpl) base);
            
            return result;
        }
        
        /*
         * Not MT-safe
         */
        private static final class PropertiesImpl implements IProperties, Cloneable
        {
            // ACCESSORS (IProperties):
            
            public String getProperty (final String key)
            {
                return getProperty (key, null);
            }
            
            public String getProperty (final String key, final String dflt)
            {
                String value = (String) m_valueMap.get (key);
                
                // first, try to delegate horizontally:
                if ((value == null) && (m_mapper != null))
                {
                    final String mappedKey = m_mapper.getMappedKey (key);
                    
                    if (mappedKey != null)
                        value = (String) m_valueMap.get (mappedKey); 
                }
                
                // next, try to delegate vertically:
                if ((value == null) && (m_delegate != null))
                {
                    value = m_delegate.getProperty (key, null); 
                }
                
                return value != null ? value : dflt;
            }
            
            public boolean isOverridden (final String key)
            {
                return m_valueMap.containsKey (key);
            }
            
            public IProperties copy ()
            {
                final PropertiesImpl _clone;
                try
                {
                    _clone = (PropertiesImpl) super.clone ();
                }
                catch (CloneNotSupportedException cnse)
                {
                    throw new Error (cnse.toString ()); // never happens
                }

                _clone.m_valueMap = (HashMap) m_valueMap.clone ();
                _clone.m_unmappedKeySet = null;
                
                // note: m_mapper field is not cloned by design (mappers are stateless/shareable)
                
                PropertiesImpl scan = _clone;
                for (PropertiesImpl delegate = m_delegate; delegate != null; delegate = delegate.m_delegate)
                {
                    final PropertiesImpl _delegateClone;
                    try
                    {
                        _delegateClone = (PropertiesImpl) delegate.clone ();
                    }
                    catch (CloneNotSupportedException cnse)
                    {
                        throw new Error (cnse.toString ()); // never happens
                    }
                    
                    // note that the value map needs to be cloned not only for the
                    // outermost IProperties set but for the inner ones as well
                    // (to prevent independent mutation in them from showing through)
                    
                    _delegateClone.m_valueMap = (HashMap) delegate.m_valueMap.clone ();
                    _delegateClone.m_unmappedKeySet = null; // ensure that the last delegate will have this field reset
                    
                    scan.setDelegate (_delegateClone);
                    scan = _delegateClone;
                }
                
                return _clone;
            }
            
            public Iterator /* String */ properties ()
            {
                return unmappedKeySet ().iterator ();
            }
            
            public Properties toProperties ()
            {
                final Properties result = new Properties ();
                
                for (Iterator i = properties (); i.hasNext (); )
                {
                    final String n = (String) i.next ();
                    final String v = getProperty (n);
                    
                    result.setProperty (n, v);
                }
                
                return result;
            }
            
            public boolean isEmpty ()
            {
                return m_valueMap.isEmpty () && ((m_delegate == null) || ((m_delegate != null) && m_delegate.isEmpty ()));
            }
            
            public String [] toAppArgsForm (final String prefix)
            {
                if (isEmpty ())
                    return IConstants.EMPTY_STRING_ARRAY;
                else
                {
                    if (prefix == null)
                        throw new IllegalArgumentException ("null input: prefix");
                    
                    final List _result = new ArrayList ();
                    for (Iterator names = properties (); names.hasNext (); )
                    {
                        final String name = (String) names.next ();
                        final String value = getProperty (name, "");
                        
                        _result.add (prefix.concat (name).concat ("=").concat (value)); 
                    }
                    
                    final String [] result = new String [_result.size ()];
                    _result.toArray (result);
                    
                    return result;
                }
            }    

            public void list (final PrintStream out)
            {
                if (out != null)
                {
                    for (Iterator i = properties (); i.hasNext (); )
                    {
                        final String n = (String) i.next ();
                        final String v = getProperty (n);
                        
                        out.println (n + ":\t[" + v + "]");
                    }
                }
            }
            
            public void list (final PrintWriter out)
            {
                if (out != null)
                {
                    for (Iterator i = properties (); i.hasNext (); )
                    {
                        final String n = (String) i.next ();
                        final String v = getProperty (n);
                        
                        out.println (n + ":\t[" + v + "]");
                    }
                }
            }
            
            // MUTATORS:
            
            public String setProperty (final String key, final String value)
            {
                if (value == null)
                    throw new IllegalArgumentException ("null input: value");
                
                m_unmappedKeySet = null;
                
                return (String) m_valueMap.put (key, value); 
            }
            
            
            PropertiesImpl (final HashMap values, final IMapper mapper)
            {
                m_mapper = mapper;
                m_valueMap = values != null ? values : new HashMap ();
                
                m_delegate = null;
            }
            
            
            Set unmappedKeySet ()
            {
                Set result = m_unmappedKeySet;
                if (result == null)
                {
                    result = new TreeSet ();
                    result.addAll (m_valueMap.keySet ());
                    if (m_delegate != null)
                        result.addAll (m_delegate.unmappedKeySet ());
                    
                    m_unmappedKeySet = result;
                    return result;
                }
                
                return result;
            }
            
            // ACCESSORS:
            
            PropertiesImpl getLastProperties ()
            {
                PropertiesImpl result = this;
                
                for (PropertiesImpl delegate = m_delegate; delegate != null; delegate = delegate.m_delegate)
                {
                    // this does not detect all possible cycles:
                    if (delegate == this)
                        throw new IllegalStateException ("cyclic delegation detected");
                    
                    result = delegate;
                }
                
                return result;
            }
            
            // MUTATORS:
            
            void setDelegate (final PropertiesImpl delegate)
            {
                m_delegate = delegate;
                
                m_unmappedKeySet = null;
            }
            
            
            private final IMapper m_mapper;
            private /*final*/ HashMap m_valueMap; // never null
            
            private PropertiesImpl m_delegate;
            private transient Set m_unmappedKeySet;
            
        } // end of nested class
        
    } // end of nested class
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------