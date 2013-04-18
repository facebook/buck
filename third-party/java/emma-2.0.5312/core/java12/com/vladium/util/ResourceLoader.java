/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ResourceLoader.java,v 1.1.1.1.2.1 2004/06/20 18:24:05 vlad_r Exp $
 */
package com.vladium.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

// ----------------------------------------------------------------------------
/**
 * A static API that can be used as a drop-in replacement for
 * java.lang.ClassLoader API (the class/resource loading part). This
 * implementation is merely a wrapper around ClassLoaderResolverget.ClassLoader()
 * method.
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class ResourceLoader
{
    // public: ................................................................
    
    /**
     * @see java.lang.ClassLoader#loadClass(java.lang.String)
     */
    public static Class loadClass (final String name)
        throws ClassNotFoundException
    {
        final Class caller = ClassLoaderResolver.getCallerClass (1);
        final ClassLoader loader = ClassLoaderResolver.getClassLoader (caller);
        
        return Class.forName (name, false, loader);
    }

    /**
     * @see java.lang.ClassLoader#getResource(java.lang.String)
     */    
    public static URL getResource (final String name)
    {
        final Class caller = ClassLoaderResolver.getCallerClass (1);
        final ClassLoader loader = ClassLoaderResolver.getClassLoader (caller);
        
        if (loader != null)
            return loader.getResource (name);
        else
            return ClassLoader.getSystemResource (name);
    }

    /**
     * @see java.lang.ClassLoader#getResourceAsStream(java.lang.String)
     */        
    public static InputStream getResourceAsStream (final String name)
    {
        final Class caller = ClassLoaderResolver.getCallerClass (1);
        final ClassLoader loader = ClassLoaderResolver.getClassLoader (caller);
        
        if (loader != null)
            return loader.getResourceAsStream (name);
        else
            return ClassLoader.getSystemResourceAsStream (name);
    }

    /**
     * @see java.lang.ClassLoader#getResources(java.lang.String)
     */            
    public static Enumeration getResources (final String name)
        throws IOException
    {
        final Class caller = ClassLoaderResolver.getCallerClass (1);
        final ClassLoader loader = ClassLoaderResolver.getClassLoader (caller);
        
        if (loader != null)
            return loader.getResources (name);
        else
            return ClassLoader.getSystemResources (name);
    }
    
    
    public static Class loadClass (final String name, final ClassLoader loader)
        throws ClassNotFoundException
    {
        return Class.forName (name, false, loader != null ? loader : ClassLoader.getSystemClassLoader ());
    }

    public static URL getResource (final String name, final ClassLoader loader)
    {
        if (loader != null)
            return loader.getResource (name);
        else
            return ClassLoader.getSystemResource (name);
    }

    public static InputStream getResourceAsStream (final String name, final ClassLoader loader)
    {
        if (loader != null)
            return loader.getResourceAsStream (name);
        else
            return ClassLoader.getSystemResourceAsStream (name);
    }

    public static Enumeration getResources (final String name, final ClassLoader loader)
        throws IOException
    {
        if (loader != null)
            return loader.getResources (name);
        else
            return ClassLoader.getSystemResources (name);
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private ResourceLoader () {} // prevent subclassing

} // end of class
// ----------------------------------------------------------------------------