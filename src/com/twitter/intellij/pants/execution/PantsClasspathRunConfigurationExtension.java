// Copyright 2015 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.execution;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.configurations.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathsList;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.twitter.intellij.pants.metrics.PantsExternalMetricsListenerManager;
import com.twitter.intellij.pants.model.TargetAddressInfo;
import com.twitter.intellij.pants.util.PantsConstants;
import com.twitter.intellij.pants.util.PantsUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class PantsClasspathRunConfigurationExtension extends RunConfigurationExtension {
  protected static final Logger LOG = Logger.getInstance(PantsClasspathRunConfigurationExtension.class);
  private static final Gson gson = new Gson();

  /**
   * The goal of this function is to find classpath for JUnit runner.
   * <p/>
   * There are two ways to do so:
   * 1. If Pants supports `--export-classpath-manifest-jar-only`, then only the manifest jar will be
   * picked up which contains all the classpath links for a particular test.
   * 2. If not, this method will collect classpath based on all known target ids from project modules.
   */
  @Override
  public <T extends RunConfigurationBase> void updateJavaParameters(
    T configuration,
    JavaParameters params,
    RunnerSettings runnerSettings
  ) throws ExecutionException {
    final Module module = findPantsModule(configuration);
    if (module == null) {
      return;
    }
    /**
     * This enables dynamic classpath for this particular run and prevents argument too long errors caused by long classpaths.
     */
    params.setUseDynamicClasspath(true);

    final PathsList classpath = params.getClassPath();

    VirtualFile manifestJar = PantsUtil.findProjectManifestJar(configuration.getProject())
      .orElseThrow(() -> new ExecutionException("Pants supports manifest jar, but it is not found."));
    classpath.add(manifestJar.getPath());


    PantsExternalMetricsListenerManager.getInstance().logTestRunner(configuration);
  }

  @NotNull
  public static List<String> findPublishedClasspath(@NotNull Module module) {
    final List<String> result = ContainerUtil.newArrayList();
    // This is type for Gson to figure the data type to deserialize
    final Type type = new TypeToken<HashSet<TargetAddressInfo>>() {}.getType();
    Set<TargetAddressInfo> targetInfoSet = gson.fromJson(module.getOptionValue(PantsConstants.PANTS_TARGET_ADDRESS_INFOS_KEY), type);
    // The new way to find classpath by target id
    for (TargetAddressInfo ta : targetInfoSet) {
      result.addAll(findPublishedClasspathByTargetId(module, ta));
    }
    return result;
  }

  @NotNull
  private static List<String> findPublishedClasspathByTargetId(@NotNull Module module, @NotNull TargetAddressInfo targetAddressInfo) {
    final Optional<VirtualFile> classpath = PantsUtil.findDistExportClasspathDirectory(module.getProject());
    if (!classpath.isPresent()) {
      return Collections.emptyList();
    }
    // Handle classpath with target.id
    List<String> paths = ContainerUtil.newArrayList();
    int count = 0;
    while (true) {
      VirtualFile classpathLinkFolder = classpath.get().findFileByRelativePath(targetAddressInfo.getId() + "-" + count);
      VirtualFile classpathLinkFile = classpath.get().findFileByRelativePath(targetAddressInfo.getId() + "-" + count + ".jar");
      if (classpathLinkFolder != null && classpathLinkFolder.isDirectory()) {
        paths.add(classpathLinkFolder.getPath());
        break;
      }
      else if (classpathLinkFile != null) {
        paths.add(classpathLinkFile.getPath());
        count++;
      }
      else {
        break;
      }
    }
    return paths;
  }

  @NotNull
  private Map<String, String> findExcludes(@NotNull Module module) {
    final Map<String, String> result = new HashMap<>();
    processRuntimeModules(
      module,
      new Processor<Module>() {
        @Override
        public boolean process(Module module) {
          final String targets = module.getOptionValue(PantsConstants.PANTS_TARGET_ADDRESSES_KEY);
          final String excludes = module.getOptionValue(PantsConstants.PANTS_LIBRARY_EXCLUDES_KEY);
          for (String exclude : PantsUtil.hydrateTargetAddresses(excludes)) {
            result.put(exclude, StringUtil.notNullize(targets, module.getName()));
          }
          return true;
        }
      }
    );
    return result;
  }

  private void processRuntimeModules(@NotNull Module module, Processor<Module> processor) {
    final OrderEnumerator runtimeEnumerator = OrderEnumerator.orderEntries(module).runtimeOnly().recursively();
    runtimeEnumerator.forEachModule(processor);
  }


  @Nullable
  private <T extends RunConfigurationBase> Module findPantsModule(T configuration) {
    if (!(configuration instanceof ModuleBasedConfiguration)) {
      return null;
    }
    final RunConfigurationModule runConfigurationModule = ((ModuleBasedConfiguration) configuration).getConfigurationModule();
    final Module module = runConfigurationModule.getModule();
    if (module == null || !PantsUtil.isPantsModule(module)) {
      return null;
    }
    return module;
  }

  @Override
  protected void readExternal(@NotNull RunConfigurationBase runConfiguration, @NotNull Element element) throws InvalidDataException {

  }

  @Override
  protected void writeExternal(@NotNull RunConfigurationBase runConfiguration, @NotNull Element element) throws WriteExternalException {

  }

  @Nullable
  @Override
  protected String getEditorTitle() {
    return PantsConstants.PANTS;
  }

  @Override
  protected boolean isApplicableFor(@NotNull RunConfigurationBase configuration) {
    return findPantsModule(configuration) != null;
  }

  @Nullable
  @Override
  protected <T extends RunConfigurationBase> SettingsEditor<T> createEditor(@NotNull T configuration) {
    return null;
  }
}
