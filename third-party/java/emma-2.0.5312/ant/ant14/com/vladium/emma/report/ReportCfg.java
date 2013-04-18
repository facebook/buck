/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ReportCfg.java,v 1.1.1.1.2.1 2004/07/08 10:52:11 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import com.vladium.util.IConstants;
import com.vladium.util.IProperties;
import com.vladium.emma.EMMAProperties;
import com.vladium.emma.ant.PropertyElement;
import com.vladium.emma.ant.SuppressableTask;
import com.vladium.emma.report.IReportEnums.DepthAttribute;
import com.vladium.emma.report.IReportEnums.UnitsTypeAttribute;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;

// ----------------------------------------------------------------------------
/**
 * ReportCfg is a container for report type {@link ReportCfg.Element}s that are
 * in turn containers for all properties that could be set on a &lt;report&gt;
 * report type configurator (&lt;txt&gt;, &lt;html&gt;, etc). The elements provide
 * the ability for report properties to be set either via the generic &lt;property&gt;
 * nested elements or dedicated attributes. Potential conflicts between the same
 * conceptual property being set via an attribute and a nested element are resolved
 * by making dedicated attributes higher priority.<P>
 * 
 * Note that ReportCfg does not handle any non-report related properties.
 * This can be done via {@link com.vladium.emma.ant.GenericCfg}. It is also the
 * parent's responsibility to merge any inherited report properties with
 * ReportCfg settings. 
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
class ReportCfg implements IReportProperties
{
    // public: ................................................................


    public static abstract class Element implements IReportEnums, IReportProperties
    {
        public void setUnits (final UnitsTypeAttribute units)
        {
            m_settings.setProperty (m_prefix.concat (UNITS_TYPE), units.getValue ());
        }
        
        public void setDepth (final DepthAttribute depth)
        {
            m_settings.setProperty (m_prefix.concat (DEPTH), depth.getValue ());
        }
        
        public void setColumns (final String columns)
        {
            m_settings.setProperty (m_prefix.concat (COLUMNS), columns);
        }
        
        public void setSort (final String sort)
        {
            m_settings.setProperty (m_prefix.concat (SORT), sort);
        }
        
        public void setMetrics (final String metrics)
        {
            m_settings.setProperty (m_prefix.concat (METRICS), metrics);
        }
        
        // not supported anymore:
        
//        public void setOutdir (final File dir)
//        {
//            // TODO: does ANT resolve files relative to current JVM dir or ${basedir}?
//            m_settings.setProperty (m_prefix.concat (OUT_DIR), dir.getAbsolutePath ());
//        }
        
        public void setOutfile (final String fileName)
        {
            m_settings.setProperty (m_prefix.concat (OUT_FILE), fileName);
        }
        
        public void setEncoding (final String encoding)
        {
            m_settings.setProperty (m_prefix.concat (OUT_ENCODING), encoding);
        }
        
        // generic property element [don't doc this publicly]:
        
        public PropertyElement createProperty ()
        {
            // TODO: error out on conficting duplicate settings
            
            final PropertyElement property = new PropertyElement ();
            m_genericSettings.add (property);
            
            return property;
        }
        
        protected abstract String getType ();
        

        Element (final Task task, final IProperties settings)
        {
            if (task == null)
                throw new IllegalArgumentException ("null input: task");
            if (settings == null)
                throw new IllegalArgumentException ("null input: settings");
            
            m_task = task;
            m_settings = settings;
            
            m_prefix = PREFIX.concat (getType ()).concat (".");
            
            m_genericSettings = new ArrayList ();
        }
        
        
        void processGenericSettings ()
        {
            for (Iterator i = m_genericSettings.iterator (); i.hasNext (); )
            {
                final PropertyElement property = (PropertyElement) i.next ();
                
                final String name = property.getName ();
                final String value = property.getValue () != null ? property.getValue () : "";
                
                if (name != null)
                {
                    final String prefixedName = m_prefix.concat (name);
                    
                    // generically named settings don't override report named settings:
                    
                    if (! m_settings.isOverridden (prefixedName))
                        m_settings.setProperty (prefixedName, value);
                }
            }
        }
        
        
        protected final Task m_task; // never null
        protected final String m_prefix; // never null
        protected final IProperties m_settings; // never null
        protected final List /* PropertyElement */ m_genericSettings; // never null
        
    } // end of nested class

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    public static class Element_HTML extends Element
    {
        protected final String getType ()
        {
            return TYPE;
        }
        
        Element_HTML (final Task task, final IProperties settings)
        {
            super (task, settings);
        }
        
        
        static final String TYPE = "html";
        
    } // end of nested class
    
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    
    public static class Element_TXT extends Element
    {
        protected final String getType ()
        {
            return TYPE;
        }
        
        Element_TXT (final Task task, final IProperties settings)
        {
            super (task, settings);
        }
        
        
        static final String TYPE = "txt";
        
    } // end of nested class
    
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    
    public static class Element_XML extends Element
    {
        protected final String getType ()
        {
            return TYPE;
        }
        
        Element_XML (final Task task, final IProperties settings)
        {
            super (task, settings);
        }
        
        
        static final String TYPE = "xml";
        
    } // end of nested class
    
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    
    
    public ReportCfg (final Project project, final Task task)
    {
        m_project = project;
        m_task = task;
        
        m_reportTypes = new ArrayList (4);
        m_cfgList = new ArrayList (4);
        m_settings = EMMAProperties.wrap (new Properties ());
    }
    
    public Path getSourcepath ()
    {
        return m_srcpath;
    }
    
    public String [] getReportTypes ()
    {
        final BuildException failure = getFailure ();
        
        if (failure != null)
            throw failure;
        else
        {
            if (m_reportTypes.isEmpty ())
                return IConstants.EMPTY_STRING_ARRAY;
            else
            {
                final String [] result = new String [m_reportTypes.size ()];
                m_reportTypes.toArray (result);
                
                return result;
            }
        }
    }
    
    public IProperties getReportSettings ()
    {
        final BuildException failure = getFailure ();
        
        if (failure != null)
            throw failure;
        else
        {
            if (! m_processed)
            {
                // collect all nested elements' generic settins into m_settings:
                
                for (Iterator i = m_cfgList.iterator (); i.hasNext (); )
                {
                    final Element cfg = (Element) i.next ();
                    cfg.processGenericSettings ();
                }
                
                m_processed = true;
            }
            
            return m_settings; // no clone
        }
    }
    
    
    // sourcepath attribute/element:
    
    public void setSourcepath (final Path path)
    {
        if (m_srcpath == null)
            m_srcpath = path;
        else
            m_srcpath.append (path);
    }
    
    public void setSourcepathRef (final Reference ref)
    {
        createSourcepath ().setRefid (ref);
    }
    
    public Path createSourcepath ()
    {
        if (m_srcpath == null)
            m_srcpath = new Path (m_project);
        
        return m_srcpath.createPath ();
    }
    
    
    // generator elements:
    
    public Element_TXT createTxt ()
    {
        return (Element_TXT) addCfgElement (Element_TXT.TYPE,
                                                     new Element_TXT (m_task, m_settings));
    }
    
    public Element_HTML createHtml ()
    {
        return (Element_HTML) addCfgElement (Element_HTML.TYPE,
                                                      new Element_HTML (m_task, m_settings));
    }
    
    public Element_XML createXml ()
    {
        return (Element_XML) addCfgElement (Element_XML.TYPE,
                                                     new Element_XML (m_task, m_settings));
    }
    
    
    // report properties [defaults for all report types]:

    public void setUnits (final UnitsTypeAttribute units)
    {
        m_settings.setProperty (PREFIX.concat (UNITS_TYPE), units.getValue ());
    }    

    public void setDepth (final DepthAttribute depth)
    {
        m_settings.setProperty (PREFIX.concat (DEPTH), depth.getValue ());
    }
    
    public void setColumns (final String columns)
    {
        m_settings.setProperty (PREFIX.concat (COLUMNS), columns);
    }
    
    public void setSort (final String sort)
    {
        m_settings.setProperty (PREFIX.concat (SORT), sort);
    }
    
    public void setMetrics (final String metrics)
    {
        m_settings.setProperty (PREFIX.concat (METRICS), metrics);
    }

    // not supported anymore:
    
//    public void setOutdir (final File dir)
//    {
//        // TODO: does ANT resolve files relative to current JVM dir or ${basedir}?
//        m_settings.setProperty (PREFIX.concat (OUT_DIR), dir.getAbsolutePath ());
//    }
//    
//    public void setDestdir (final File dir)
//    {
//        // TODO: does ANT resolve files relative to current JVM dir or ${basedir}?
//        m_settings.setProperty (PREFIX.concat (OUT_DIR), dir.getAbsolutePath ());
//    }
    
    public void setOutfile (final String fileName)
    {
        m_settings.setProperty (PREFIX.concat (OUT_FILE), fileName);
    }
    
    public void setEncoding (final String encoding)
    {
        m_settings.setProperty (PREFIX.concat (OUT_ENCODING), encoding);
    }
    
    // protected: .............................................................
    
    
    protected Element addCfgElement (final String type, final Element cfg)
    {
        if (m_reportTypes.contains (type))
        {
            setFailure ((BuildException) SuppressableTask.newBuildException (m_task.getTaskName ()
                + ": duplicate configuration for report type [" + type + "]" ,
                m_task.getLocation ()).fillInStackTrace ());
        }
        else
        {
            m_reportTypes.add (type);
            m_cfgList.add (cfg);
        }
        
        return cfg;
    }

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private void setFailure (final BuildException failure)
    {
        if (m_settingsFailure == null) m_settingsFailure = failure; // record the first one only
    }
    
    private BuildException getFailure ()
    {
        return m_settingsFailure;
    }
    
    
    private final Project m_project;
    private final Task m_task;
    
    private final List /* report type:String */ m_reportTypes; // using a list to keep the generation order same as configuration
    private final List /* Element */ m_cfgList;
    private final IProperties m_settings; // never null
    
    private Path m_srcpath;
    
    private transient BuildException m_settingsFailure; // can be null
    private transient boolean m_processed;

} // end of class
// ----------------------------------------------------------------------------