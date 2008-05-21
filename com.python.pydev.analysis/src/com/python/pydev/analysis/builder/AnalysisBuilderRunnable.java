/*
 * Created on Apr 6, 2006
 */
package com.python.pydev.analysis.builder;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.text.IDocument;
import org.python.pydev.builder.PyDevBuilderVisitor;
import org.python.pydev.core.IModule;
import org.python.pydev.editor.codecompletion.revisited.modules.SourceModule;
import org.python.pydev.plugin.PydevPlugin;
import org.python.pydev.plugin.nature.PythonNature;

import com.python.pydev.analysis.AnalysisPreferences;
import com.python.pydev.analysis.IAnalysisPreferences;
import com.python.pydev.analysis.OccurrencesAnalyzer;
import com.python.pydev.analysis.additionalinfo.AbstractAdditionalInterpreterInfo;
import com.python.pydev.analysis.additionalinfo.AdditionalProjectInterpreterInfo;
import com.python.pydev.analysis.messages.IMessage;

/**
 * This class is used to do analysis on a thread, so that if an analysis is asked for some analysis that
 * is already in progress, that analysis will be stopped and this one will begin.
 * 
 * @author Fabio
 */
public class AnalysisBuilderRunnable implements Runnable{
    
    /**
     * Field that should know all the threads.
     */
    private volatile static Map<String, AnalysisBuilderRunnable> availableThreads;
    
    /**
     * @return Returns the availableThreads.
     */
    private static synchronized Map<String, AnalysisBuilderRunnable> getAvailableThreads() {
        if(availableThreads == null){
            availableThreads = Collections.synchronizedMap(new HashMap<String, AnalysisBuilderRunnable>());
        }
        return availableThreads;
    }
    
    private synchronized void removeFromThreads() {
        Map<String, AnalysisBuilderRunnable> available = getAvailableThreads();
        synchronized(available){
            AnalysisBuilderRunnable analysisBuilderThread = available.get(moduleName);
            if(analysisBuilderThread == this){
                available.remove(moduleName);
            }
        }
    }


    /**
     * Creates a thread for analyzing some module (and stopping analysis of some other thread if there is one
     * already running).
     *  
     * @param moduleName the name of the module
     * @return a builder thread.
     */
    public static synchronized AnalysisBuilderRunnable createRunnable(IDocument document, IResource resource, IModule module, boolean analyzeDependent, IProgressMonitor monitor, boolean isFullBuild, String moduleName){
        Map<String, AnalysisBuilderRunnable> available = getAvailableThreads();
        synchronized(available){
            AnalysisBuilderRunnable analysisBuilderThread = available.get(moduleName);
            if(analysisBuilderThread != null){
                //there is some existing thread that we have to stop to create the new one
                analysisBuilderThread.stopAnalysis();
            }
            analysisBuilderThread = new AnalysisBuilderRunnable(document, resource, module, analyzeDependent, monitor, isFullBuild, moduleName);
            available.put(moduleName, analysisBuilderThread);
            return analysisBuilderThread;
        }
    }
    
    // -------------------------------------------------------------------------------------------- ATTRIBUTES

    private IDocument document;
    private WeakReference<IResource> resource;
    private IModule module;
    private boolean analyzeDependent;
    private IProgressMonitor monitor;
    private IProgressMonitor internalCancelMonitor;
    private boolean isFullBuild;
    private String moduleName;
    
    // ---------------------------------------------------------------------------------------- END ATTRIBUTES
    
    
    public AnalysisBuilderRunnable(IDocument document, IResource resource, IModule module, boolean analyzeDependent, IProgressMonitor monitor, boolean isFullBuild, String moduleName) {
        this.document = document;
        this.resource = new WeakReference<IResource>(resource);
        this.module = module;
        this.analyzeDependent = analyzeDependent;
        this.monitor = monitor;
        this.isFullBuild = isFullBuild;
        this.moduleName = moduleName;
        this.internalCancelMonitor = new NullProgressMonitor();
    }

    private void dispose() {
        this.document = null;
        this.resource = null;
        this.module = null;
        this.monitor = null;
        this.moduleName = null;
        this.internalCancelMonitor = null;
    }
    
    public void stopAnalysis() {
        this.internalCancelMonitor.setCanceled(true);
    }
    
    private void checkStop(){
        if(this.internalCancelMonitor.isCanceled() || monitor.isCanceled()){
            throw new OperationCanceledException();
        }
    }
    
    public void run() {
        try{
            doAnalysis();
        }catch(NoClassDefFoundError e){
            //ignore, plugin finished and thread still active
        }
    }
    
    public void doAnalysis(){
        try {
            //if the resource is not open, there's not much we can do...
            IResource r = resource.get();
            if(r == null || !r.getProject().isOpen()){
                return;
            }
            
            AnalysisRunner runner = new AnalysisRunner();
            checkStop();
            
            IAnalysisPreferences analysisPreferences = AnalysisPreferences.getAnalysisPreferences();
            //update the severities, etc.
            analysisPreferences.clearCaches();

            boolean makeAnalysis = true;
            if (!runner.canDoAnalysis(document) || !PyDevBuilderVisitor.isInPythonPath(r) || //just get problems in resources that are in the pythonpath
                    analysisPreferences.makeCodeAnalysis() == false //let's see if we should do code analysis
            ) {
                synchronized(r){
                    runner.deleteMarkers(r);
                }
                makeAnalysis = false;
            }

            checkStop();
            PythonNature nature = PythonNature.getPythonNature(r);

            //remove dependency information (and anything else that was already generated), but first, gather the modules dependent on this one.
            if(!isFullBuild){
            	//if it is a full build, that info is already removed
            	AnalysisBuilderVisitor.fillDependenciesAndRemoveInfo(moduleName, nature, analyzeDependent, monitor, isFullBuild);
            }
            recreateCtxInsensitiveInfo(r, document, module, nature);
            
            
            //let's see if we should continue with the process
            if(!makeAnalysis){
                return;
            }

            //monitor.setTaskName("Analyzing module: " + moduleName);
            monitor.worked(1);

            checkStop();
            OccurrencesAnalyzer analyzer = new OccurrencesAnalyzer();

            //ok, let's do it
            checkStop();
            IMessage[] messages = analyzer.analyzeDocument(nature, (SourceModule) module, analysisPreferences, document, this.internalCancelMonitor);
            //monitor.setTaskName("Adding markers for module: "+moduleName);
            monitor.worked(1);
            
            //last chance to stop...
            checkStop();
            
            //don't stop after setting to add / remove the markers
            r = resource.get();
            if(r != null){
                runner.setMarkers(r, document, messages);
            }
        } catch (OperationCanceledException e) {
            //ok, ignore it
            //System.out.println("Ok, canceled previous");
        } catch (Exception e){
            PydevPlugin.log(e);
        } finally{
            try{
                removeFromThreads();
            }catch (Throwable e){
                PydevPlugin.log(e);
            }
            dispose();
        }
    }

    
    private void recreateCtxInsensitiveInfo(IResource resource, IDocument document, IModule sourceModule, PythonNature nature) {
    	if(resource == null){
    		return;
    	}
        AbstractAdditionalInterpreterInfo info = AdditionalProjectInterpreterInfo.getAdditionalInfoForProject(nature.getProject());
        if(info == null){
        	return;
        }
        
        //info.removeInfoFromModule(sourceModule.getName()); -- does not remove info from the module because this should be already
        //done once it gets here (the AnalysisBuilder, that also makes dependency info should take care of this).
        boolean generateDelta;
        if(isFullBuild){
            generateDelta = false;
        }else{
            generateDelta = true;
        }
        
        if (sourceModule instanceof SourceModule) {
            SourceModule m = (SourceModule) sourceModule;
            info.addSourceModuleInfo(m, nature, generateDelta);
        }
    }


}
