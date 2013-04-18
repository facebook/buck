/* Copyright (C) 2004 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ClassDep.java,v 1.1.2.2 2004/07/09 01:42:04 vlad_r Exp $
 */
package com.vladium.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.cls.IConstantCollection;
import com.vladium.jcd.cls.constant.CONSTANT_Class_info;
import com.vladium.jcd.cls.constant.CONSTANT_info;
import com.vladium.jcd.parser.ClassDefParser;
import com.vladium.util.ByteArrayOStream;
import com.vladium.util.Descriptors;

// ----------------------------------------------------------------------------
/**
 * TODO: doc
 * 
 * @author Vlad Roubtsov, (C) 2004
 */
public class ClassDep
{
    // public: ................................................................
    
    public static void main (final String [] args)
        throws Exception
    {
        if (args.length < 2)
            throw new IllegalArgumentException ("usage: classpath output_file rootset_classname_1 [rootset_classname_2 ...]");
            
        final String _classPath = args [0];
        final URL [] classPath;
        {
            final StringTokenizer tokenizer = new StringTokenizer (_classPath, File.pathSeparator);
            classPath = new URL [tokenizer.countTokens ()];
            
            for (int i = 0; tokenizer.hasMoreTokens (); ++ i)
            {
                classPath [i] = new File (tokenizer.nextToken ()).toURL ();
            }
        }
        
        final File outFile = new File (args [1]);
        
        final int offset = 2;
        final String [] rootSet = args.length > offset
            ? new String [args.length - offset]
            : new String [0];
        {
            for (int a = 0; a < rootSet.length; ++ a)
            {
                rootSet [a] = args [a + offset];
            }
        }
        
        final ClassDep _this = new ClassDep (rootSet, classPath);
        final String [] deps = _this.getDependencies (true);
        
        final StringBuffer s = new StringBuffer (); 
        for (int d = deps.length - 1; d >= 0; -- d) // reverse topological order
        {
            s.append (deps [d]);
            if (d > 0) s.append (',');
        }
        
        final File parent = outFile.getParentFile ();
        if (parent != null) parent.mkdirs ();
        
        final FileOutputStream out = new FileOutputStream (outFile);
        
        final Properties result = new Properties ();
        result.setProperty ("closure", s.toString ());
        
        result.store (out, "this file is auto-generated, do not edit");
        
        out.close ();
    }
    
    
    public ClassDep (final String [] rootSet, final URL [] classPath)
    {
        if (rootSet == null)
            throw new IllegalArgumentException ("null input: rootSet");
        
        if (classPath == null)
            throw new IllegalArgumentException ("null input: classPath");
        
        m_rootSet = rootSet;
        m_classPath = classPath;
    }
    
    public String [] getDependencies (final boolean includeRootSet)
        throws IOException
    {
        final Set /* class Java name:String */ _result = new HashSet ();
        final ClassLoader loader = new URLClassLoader (m_classPath, null);
        
        if (includeRootSet)
        {
            for (int i = 0; i < m_rootSet.length; ++ i)
            {
                _result.add (m_rootSet [i]);
            }
        }
        
        final LinkedList /* class VM name:String */ queue = new LinkedList ();
        for (int i = 0; i < m_rootSet.length; ++ i)
        {
            queue.add (Descriptors.javaNameToVMName (m_rootSet [i]));
        }
        
        final ByteArrayOStream baos = new ByteArrayOStream (8 * 1024);
        final byte [] readbuf = new byte [8 * 1024];
        
        while (! queue.isEmpty ())
        {
            final String classVMName = (String) queue.removeFirst ();
            
            // keep at most one file descriptor open:
            
            InputStream in = null;
            try
            {
                // NOTE: getResources() not used
                in = loader.getResourceAsStream (classVMName + ".class");
                
                if (in == null)
                {
                    throw new IllegalArgumentException ("class [" + Descriptors.vmNameToJavaName (classVMName) + "] not found in the input classpath");
                }
                else
                {
                    baos.reset ();
                    for (int read; (read = in.read (readbuf)) >= 0; baos.write (readbuf, 0, read));
                }
            }
            finally
            {
                if (in != null) try { in.close (); } catch (IOException ignore) { ignore.printStackTrace (); }
            }
            in = null;

            final ClassDef cls = ClassDefParser.parseClass (baos.getByteArray (), baos.size ());
            final List /* class VM name:String */ clsDeps  = getCONSTANT_Class_info (cls);
            
            for (Iterator i = clsDeps.iterator (); i.hasNext (); )
            {
                final String classDepVMName = (String) i.next ();
                
                if (classDepVMName.startsWith ("com/vladium/")) // TODO: generic filtering
                {
                    if (_result.add (Descriptors.vmNameToJavaName (classDepVMName)))
                    {
                        queue.addLast (classDepVMName);
                    }
                }
            }
        }
        
        final String [] result = new String [_result.size ()];
        _result.toArray (result);
        
        return result;
    }
    
    /**
     * @return array of class VM names [may contain duplicates]
     */
    public static List getCONSTANT_Class_info (final ClassDef cls)
    {
        if (cls == null)
            throw new IllegalArgumentException ("null input: cls");
        
        final IConstantCollection constants = cls.getConstants ();
        final IConstantCollection.IConstantIterator i = constants.iterator ();
        
        final List result = new ArrayList ();

        for (CONSTANT_info entry; (entry = i.nextConstant ()) != null; )
        {
            if (entry instanceof CONSTANT_Class_info)
            {
                result.add (((CONSTANT_Class_info) entry).getName (cls));
            }
        }
        
        return result;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................


    private final String [] m_rootSet;
    private final URL [] m_classPath;
    
} // end of class
// ----------------------------------------------------------------------------