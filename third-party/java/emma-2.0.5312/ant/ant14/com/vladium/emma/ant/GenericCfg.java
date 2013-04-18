/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: GenericCfg.java,v 1.1.1.1.2.1 2004/07/08 10:52:10 vlad_r Exp $
 */
package com.vladium.emma.ant;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

import com.vladium.emma.EMMAProperties;
import com.vladium.util.IProperties;
import com.vladium.util.Property;

// ----------------------------------------------------------------------------
/**
 * GenericCfg is a simple container for 'generic' properties, i.e., properties
 * that are set via generic 'properties=&lt;file&gt;' attribute and &lt;property&gt;
 * nested elements. This class makes no decision about relative priorities for
 * propertie set in an external file or via nested elements, leaving this up
 * to the parent.
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
class GenericCfg
{
    // public: ................................................................
    

    public GenericCfg (final Task task)
    {
        if (task == null) throw new IllegalArgumentException ("null input: task");
        
        m_task = task;
        m_genericPropertyElements = new ArrayList ();
    }
    

    // .properties file attribute [actual file I/O done lazily by getFileSettings()]:
    
    public void setProperties (final File file)
    {
        m_settingsFile = file; // actual file I/O is done in getFileSettings()
    }

    // generic property element:
    
    public PropertyElement createProperty ()
    {
        m_genericSettings = null;
        
        final PropertyElement property = new PropertyElement ();
        m_genericPropertyElements.add (property);
        
        return property;
    }

    // ACCESSORS:

    public IProperties getFileSettings ()
    {
        IProperties fileSettings = m_fileSettings;
        if ((fileSettings == null) && (m_settingsFile != null))
        {
            try
            {
                fileSettings = EMMAProperties.wrap (Property.getPropertiesFromFile (m_settingsFile));
            }
            catch (IOException ioe)
            {
                throw (BuildException) SuppressableTask.newBuildException (m_task.getTaskName ()
                    + ": property file [" + m_settingsFile.getAbsolutePath () + "] could not be read" , ioe, m_task.getLocation ()).fillInStackTrace ();
            }
            
            m_fileSettings = fileSettings;
            
            return fileSettings;
        }
        
        return fileSettings;
    }
    
    public IProperties getGenericSettings ()
    {
        IProperties genericSettings = m_genericSettings;
        if (genericSettings == null)
        {
            genericSettings = EMMAProperties.wrap (new Properties ());
            
            for (Iterator i = m_genericPropertyElements.iterator (); i.hasNext (); )
            {
                final PropertyElement property = (PropertyElement) i.next ();
                
                final String name = property.getName ();
                String value = property.getValue ();
                if (value == null) value = "";
                
                if (name != null)
                {
                    // [assertion: name != null, value != null]
                    
                    final String currentValue = genericSettings.getProperty (name);  
                    if ((currentValue != null) && ! value.equals (currentValue))
                    {
                        throw (BuildException) SuppressableTask.newBuildException (m_task.getTaskName ()
                            + ": conflicting settings for property [" + name + "]: [" + value + "]" , m_task.getLocation ()).fillInStackTrace ();
                    }
                    else
                    {
                        genericSettings.setProperty (name, value);
                    }
                }
            }
            
            m_genericSettings = genericSettings;
            
            return genericSettings;
        }
        
        return genericSettings;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private final Task m_task;
        
    private final List /* PropertyElement */ m_genericPropertyElements; // never null
    private File m_settingsFile; // can be null
    
    private transient IProperties m_fileSettings; // can be null
    private transient IProperties m_genericSettings; // can be null

} // end of class
// ----------------------------------------------------------------------------