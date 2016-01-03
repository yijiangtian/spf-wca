package wcanalysis.heuristic;

import isstac.structure.cfg.Block;
import isstac.structure.cfg.CFG;
import isstac.structure.cfg.CFGGenerator;
import isstac.structure.cfg.CachingCFGGenerator;
import isstac.structure.cfg.util.CFGToDOT;
import isstac.structure.cfg.util.DotAttribute;
import isstac.structure.serialize.GraphSerializer;
import isstac.structure.serialize.JavaSerializer;
import wcanalysis.heuristic.ContextManager.CGContext;
import wcanalysis.heuristic.DecisionCollection.FalseDecisionCollection;
import wcanalysis.heuristic.DecisionCollection.TrueDecisionCollection;
import wcanalysis.heuristic.util.PathVisualizer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import att.grappa.Attribute;
import att.grappa.Graph;
import gov.nasa.jpf.Config;
import gov.nasa.jpf.JPF;
import gov.nasa.jpf.PropertyListenerAdapter;
import gov.nasa.jpf.jvm.bytecode.DoubleCompareInstruction;
import gov.nasa.jpf.jvm.bytecode.FCMPL;
import gov.nasa.jpf.jvm.bytecode.IfInstruction;
import gov.nasa.jpf.search.Search;
import gov.nasa.jpf.symbc.bytecode.FCMPG;
import gov.nasa.jpf.symbc.bytecode.LCMP;
import gov.nasa.jpf.symbc.bytecode.IF_ICMPEQ;
import gov.nasa.jpf.symbc.bytecode.IF_ICMPGE;
import gov.nasa.jpf.symbc.bytecode.IF_ICMPGT;
import gov.nasa.jpf.symbc.bytecode.IF_ICMPLE;
import gov.nasa.jpf.symbc.bytecode.IF_ICMPLT;
import gov.nasa.jpf.symbc.bytecode.IF_ICMPNE;
import gov.nasa.jpf.symbc.numeric.PCChoiceGenerator;
import gov.nasa.jpf.symbc.numeric.PathCondition;
import gov.nasa.jpf.vm.ChoiceGenerator;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.MethodInfo;
import gov.nasa.jpf.vm.StackFrame;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.VM;

/**
 * @author Kasper Luckow
 */
public abstract class PathListener extends PropertyListenerAdapter {
  
  private Logger logger = JPF.getLogger(PathListener.class.getName());

  /*
   * Configuration
   */
  //JPF conf strings
  //Measured methods
  public final static String MEASURED_METHODS = "symbolic.heuristic.measuredmethods";
  private final static String SYMBOLIC_METHODS = "symbolic.method";
  private Set<String> measuredMethods;
  private Set<String> symbolicMethods;
  
  public final static String POLICY_GENERATOR_CLS_CONF = "symbolic.wc.policygenerator";
  public final static String HISTORY_SIZE_CONF = "symbolic.wc.policy.history.size";
  protected final static int DEF_HISTORY_SIZE = 0; 
  
  //Visualization
  public final static String SHOW_INSTRS_CONF = "symbolic.wc.visualizer.showinstructions";  
  
  //Notion of worst case
  public final static String WORST_CASE_STATE_BLDR_CONF = "symbolic.wc.statebuilder";
  
  /*
   * State
   */
  //Conf state
  
  private final Config jpfConf;
  //Visualization
  private File visDir;
  private final boolean showInstrs;
  
  //Serialization
  private File serDir;
  
  //State
  private StateBuilder stateBuilder;
  protected WorstCasePath wcPath;
  protected ContextManager ctxManager;
  
  protected PolicyGenerator<?> policyGenerator;

  public PathListener(Config jpfConf, JPF jpf) {
    this.jpfConf = jpfConf;
    
    //TODO: we can make this even more precise by also allowing specifying the EXACT 
    //call (e.g. src line number + class) after which count collection should start 
    if(!jpfConf.hasValue(MEASURED_METHODS) && !jpfConf.hasValue(SYMBOLIC_METHODS)) {
      RuntimeException e = new RuntimeException("Must set either " + MEASURED_METHODS + " or " + SYMBOLIC_METHODS);
      logger.severe(e.getMessage());
      throw e;
    }
    this.measuredMethods = getMeasuredMethods(this.jpfConf);
    this.symbolicMethods = getSymbolicMethods(this.jpfConf);
    logger.info("Measured methods: " + this.measuredMethods.toString());
    
    this.serDir = this.getSerializationDir(jpfConf);
    if(this.visualize(this.jpfConf))
      this.visDir = this.getVisualizationDir(this.jpfConf);
    
    this.showInstrs = jpfConf.getBoolean(SHOW_INSTRS_CONF, false);
    
    //Initialize state
    if(jpfConf.hasValue(WORST_CASE_STATE_BLDR_CONF)) {
      this.stateBuilder = jpfConf.getInstance(WORST_CASE_STATE_BLDR_CONF, StateBuilder.class);
    } else
      this.stateBuilder = new TimeStateBuilder();
    
    if(jpfConf.hasValue(POLICY_GENERATOR_CLS_CONF)) {
      this.policyGenerator = jpfConf.getInstance(POLICY_GENERATOR_CLS_CONF, PolicyGenerator.class);
    } else {
      int historySize = jpfConf.getInt(HISTORY_SIZE_CONF, DEF_HISTORY_SIZE);
      this.policyGenerator = new HistoryBasedPolicyGenerator(historySize);
    }
    
    this.ctxManager = new ContextManager();
    
    this.wcPath = null;
  }
  
  private Set<String> getMeasuredMethods(Config jpfConf) {
    String[] measMeth = jpfConf.getStringArray(MEASURED_METHODS, jpfConf.getStringArray(SYMBOLIC_METHODS));
    return extractSimpleMethodNames(measMeth);
  }
  
  private Set<String> getSymbolicMethods(Config jpfConf) {
    String[] symMeth = jpfConf.getStringArray(SYMBOLIC_METHODS, jpfConf.getStringArray(MEASURED_METHODS));
    return extractSimpleMethodNames(symMeth); 
  }
  
  private Set<String> extractSimpleMethodNames(String[] jpfMethodSpecs) {
    //FIXME: This also means that we do not distinguish between overloaded methods
    String[] processedMethods = new String[jpfMethodSpecs.length];
    System.arraycopy(jpfMethodSpecs, 0, processedMethods, 0, jpfMethodSpecs.length);
    for(int i = 0; i < jpfMethodSpecs.length; i++) {
      String meth = jpfMethodSpecs[i];
      int sigBegin = meth.indexOf('(');
      if(sigBegin >= 0)
        processedMethods[i] = meth.substring(0, sigBegin);
    }
    return new HashSet<String>(Arrays.asList(processedMethods));
  }
  
  protected boolean isInCallStack(VM vm, ThreadInfo thread, Set<String> tgts) {
    for(StackFrame frame : thread.getInvokedStackFrames()) {
      String meth = frame.getMethodInfo().getBaseName();
      if(tgts.contains(meth))
        return true;
    }
    return false; 
  }
  
  protected boolean isInSymbolicCallStack(VM vm, ThreadInfo thread) {
    return isInCallStack(vm, thread, this.symbolicMethods);
  }
  
  protected boolean isInMeasuredMethodCallStack(VM vm, ThreadInfo thread) {
    return isInCallStack(vm, thread, this.measuredMethods);
  }
  
  @Override
  public void choiceGeneratorAdvanced (VM vm, ChoiceGenerator<?> currentCG) {
    //TODO: check if there is a difference between the following cg and currentCg passed to this method
    ChoiceGenerator<?> cg = vm.getSystemState().getChoiceGenerator();
    if(cg instanceof PCChoiceGenerator) {
      this.stateBuilder.handleChoiceGeneratorAdvanced(vm, currentCG);
      CGContext ctx = this.ctxManager.getContext(cg);
      if(ctx == null) {        
        //TODO: Should it be CG or currentCG here?
        this.ctxManager.addContext(cg, vm.getCurrentThread().getCallerStackFrame(), this.stateBuilder.copy());
      } else {
        this.stateBuilder = ctx.stateBuilder;
      }
    }
  }
  
  @Override
  public void searchFinished(Search search) {
    searchFinished(this.wcPath);
  }
  
  public void searchFinished(WorstCasePath wcPath) {
    if(wcPath == null)
      return;
    
    Policy policy = this.policyGenerator.generate(wcPath);
    
    String tgtOutputfileName = "";
    for(String measuredMethod : measuredMethods)
      tgtOutputfileName += measuredMethod.substring(0, measuredMethod.lastIndexOf("("));
    
    if(serialize(jpfConf)) {
      File of = new File(this.serDir, tgtOutputfileName + ".pol");
      try(OutputStream fo = new FileOutputStream(of)) {
        policy.save(fo);
      } catch (IOException e) {
        logger.severe(e.getMessage());
        throw new RuntimeException(e);
      }
    }
    
    if(visualize(jpfConf)) {      
      //we project the worst case path on the cfg and output it
      String[] classpaths = jpfConf.getProperty("classpath").split(",");
      CFGGenerator cfgGen = new CachingCFGGenerator(classpaths);
      PathProjector sequenceVisualizer = new PathVisualizer(cfgGen);
      Collection<CFG> transformedCFGs = sequenceVisualizer.projectPath(wcPath);
      for(CFG cfg : transformedCFGs) {
        visualize(cfg, this.showInstrs, new File(this.visDir, getBaseFileName(cfg)));
      }
      
      //output the path to text file
      visualize(wcPath, new File(this.visDir, "wcpath_" + tgtOutputfileName + ".txt"));
    }
  }

  @Override
  public void executeInstruction(VM vm, ThreadInfo currentThread, Instruction instructionToExecute) {
    if(isInMeasuredMethodCallStack(vm, currentThread)) {
      if(!currentThread.isFirstStepInsn()) {
        this.stateBuilder.handleExecuteInstruction(vm, currentThread, instructionToExecute);
      }
    }
  }
  
  @Override
  public void instructionExecuted(VM vm, ThreadInfo currentThread, Instruction nextInstruction, Instruction executedInstruction) {
    if(isInMeasuredMethodCallStack(vm, currentThread) && !currentThread.isFirstStepInsn()) {
      this.stateBuilder.handleInstructionExecuted(vm, currentThread, nextInstruction, executedInstruction);
    }
  }

  @Override
  public void exceptionThrown(VM vm, ThreadInfo currentThread, ElementInfo thrownException) {
    checkExecutionPath(vm);
  }

  @Override
  public void searchConstraintHit(Search search) {
    if(!search.isEndState() && !search.isErrorState()) {
      checkExecutionPath(search.getVM());
    }
  }

  @Override
  public void stateAdvanced(Search search) {
    if(search.isEndState() && !search.isIgnoredState()) {
      checkExecutionPath(search.getVM());
    }
  }

  private void checkExecutionPath(VM vm) {
    PathCondition pc = PathCondition.getPC(vm);
    
    PathCondition pcNew = null;
    if(pc != null) {
      pcNew = pc.make_copy();
    }
    State currentState = this.stateBuilder.build(pcNew);
    WorstCasePath currentWcPath = new WorstCasePath(currentState, vm.getSystemState().getChoiceGenerator(), this.ctxManager);
    
    if(currentWcPath.compareTo(this.wcPath) > 0) {
      this.wcPath = currentWcPath;
    }
  }

  public WorstCasePath getWcPath() {
    return wcPath;
  }
  
  protected String getBaseFileName(CFG cfg) {
    String baseFileName = cfg.getFqMethodName().replaceAll("[^\\p{Alpha}]+","");
    if(jpfConf.hasValue("target.args")) //we assume that the single parameter denotes the input size
      baseFileName += "_inputsize_" + jpfConf.getString("target.args");
    return baseFileName;
  }
  
  protected void visualize(Path path, File wcPathFile) {
    logger.info("writing wc path to file: " + wcPathFile.getAbsolutePath());
    try(PrintWriter out = new PrintWriter(wcPathFile)) {
      out.println(path.toString());
    } catch (FileNotFoundException e) {
      logger.warning(e.getMessage());
    }    
  }
  
  protected void visualize(CFG cfg, boolean showInstrs, File outputFile) {
    CFGToDOT dotVis = new CFGToDOT();
    //Write dot file
    Graph dotGraph = dotVis.build(cfg, showInstrs);
    try {
      dotGraph.printGraph(new FileOutputStream(outputFile));
      logger.info("writing dot file to: " + outputFile.getAbsolutePath());
      try {
        //this will fail on windows likely -- we just catch the exception and continue
        CFGToDOT.dot2pdf(outputFile);
      } catch(Exception e) {
        logger.warning(e.getMessage());
      }
    } catch (FileNotFoundException e) {
      logger.warning(e.getMessage());
    }
  }
  
  public abstract boolean visualize(Config jpfConf);
  public abstract File getVisualizationDir(Config jpfConf);
  public abstract boolean serialize(Config jpfConf);
  public abstract File getSerializationDir(Config jpfConf);
}
