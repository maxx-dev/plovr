package org.plovr;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.JSSourceFile;


/**
 * {@link Manifest} represents an ordered list of JavaScript inputs to the
 * Closure Compiler, along with a set of externs.
 *
 * @author bolinfest@gmail.com (Michael Bolin)
 */
public final class Manifest {

  private static final Logger logger = Logger.getLogger("org.plovr.Manifest");

  /**
   * Converts a plovr JsInput to a Closure Compiler JSSourceFile.
   */
  private static Function<JsInput, JSSourceFile> inputToSourceFile =
      new Function<JsInput, JSSourceFile>() {
    @Override
    public JSSourceFile apply(JsInput jsInput) {
      return JSSourceFile.fromGenerator(jsInput.getName(), jsInput);
    }
  };

  private final File closureLibraryDirectory;
  private final Set<File> dependencies;
  private final List<JsInput> requiredInputs;
  private final Set<File> externs;

  /**
   * 
   * @param closureLibraryDirectory Directory that is the root of the Closure
   *        Library (must contain base.js)
   * @param dependencies files (or directories) that contain JS inputs that
   *        may be included in the compilation
   * @param requiredInputs files (or directories) that contain JS inputs that
   *        must be included in the compilation 
   * @param externs files (or directories) that contain JS externs
   */
  Manifest(
      @Nullable File closureLibraryDirectory,
      List<File> dependencies,
      List<JsInput> requiredInputs,
      @Nullable List<File> externs) {
    Preconditions.checkNotNull(dependencies);
    Preconditions.checkNotNull(requiredInputs);

    // TODO(bolinfest): Monitor directories for changes and have the JsInput
    // mark itself dirty when there is a change.
    this.closureLibraryDirectory = closureLibraryDirectory;
    this.dependencies = ImmutableSet.copyOf(dependencies);
    this.requiredInputs = ImmutableList.copyOf(requiredInputs);
    this.externs = externs == null ? null : ImmutableSet.copyOf(externs);
  }

  /**
   * @return a new {@link CompilerArguments} that reflects the configuration for
   *         this {@link Manifest}.
   */
  public CompilerArguments getCompilerArguments() {
    List<JSSourceFile> externs = (this.externs == null)
        ? getDefaultExterns()
        : Lists.transform(getExternInputs(), inputToSourceFile);

    List<JsInput> jsInputs = getInputsInCompilationOrder();
    List<JSSourceFile> inputs = Lists.transform(jsInputs, inputToSourceFile);
    logger.info("Inputs: " + jsInputs.toString());

    return new CompilerArguments(externs, inputs);
  }

  private List<JSSourceFile> getDefaultExterns() {
    // TODO(bolinfest): Implement this.
    logger.info("Returning default externs");
    return null;
  }

  private List<JsInput> getInputsInCompilationOrder() {
    Set<JsInput> allDependencies = getAllDependencies();

    // Build up the dependency graph.
    Map<String, JsInput> provideToSource = Maps.newHashMap();
    for (JsInput input : allDependencies) {
      List<String> provides = input.getProvides();
      for (String provide : provides) {
        JsInput existingProvider = provideToSource.get(provide);
        if (existingProvider != null) {
          throw new IllegalStateException(provide + " is provided by both " +
              existingProvider + " and " + input);
        }
        provideToSource.put(provide, input);
      }
    }

    LinkedHashSet<JsInput> compilerInputs = new LinkedHashSet<JsInput>();
    if (closureLibraryDirectory != null) {
      String path = "base.js";
      JsInput base = new JsSourceFile(path, new File(closureLibraryDirectory, path));
      compilerInputs.add(base);
    }
    for (JsInput requiredInput : requiredInputs) {
      buildDependencies(provideToSource, compilerInputs, requiredInput);
    }
    
    return ImmutableList.copyOf(compilerInputs);
  }

  private void buildDependencies(Map<String, JsInput> provideToSource,
      LinkedHashSet<JsInput> transitiveDependencies, JsInput input) {
    for (String require : input.getRequires()) {
      JsInput provide = provideToSource.get(require);
      if (provide == null) {
        throw new IllegalStateException("No provider for: " + require);
      }
      buildDependencies(provideToSource, transitiveDependencies, provide);
    }
    transitiveDependencies.add(input);
  }

  private Set<JsInput> getAllDependencies() {
    Set<JsInput> allDependencies = Sets.newHashSet();
    final boolean includeSoy = true;
    if (closureLibraryDirectory != null) {
      allDependencies.addAll(getFiles(closureLibraryDirectory, includeSoy));
    }
    allDependencies.addAll(getFiles(dependencies, includeSoy));
    allDependencies.addAll(requiredInputs);
    return allDependencies;
  }
  
  private List<JsInput> getExternInputs() {
    final boolean includeSoy = false;
    List<JsInput> externInputs = Lists.newArrayList(getFiles(externs,
        includeSoy));
    return ImmutableList.copyOf(externInputs);
  }

  private Set<JsInput> getFiles(File fileToExpand, boolean includeSoy) {
    return getFiles(Sets.newHashSet(fileToExpand), includeSoy);
  }

  private Set<JsInput> getFiles(Set<File> filesToExpand, boolean includeSoy) {
    Set<JsInput> inputs = Sets.newHashSet();
    for (File file : filesToExpand) {
      getInputs(file, "", inputs, includeSoy);
    }
    return ImmutableSet.copyOf(inputs);
  }

  private void getInputs(File file, String path, Set<JsInput> output,
      boolean includeSoy) {
    Preconditions.checkArgument(file.exists());
    if (file.isFile()) {
      String fileName = file.getName();
      if (fileName.endsWith(".js") || (includeSoy && fileName.endsWith(".soy"))) {
        output.add(LocalFileJsInput.createForFileWithName(file, path + "/" + fileName));
      }
    } else if (file.isDirectory()) {
      path += "/" + file.getName();
      for (File entry : file.listFiles()) {
        getInputs(entry, path, output, includeSoy);
      }
    }
  }
}