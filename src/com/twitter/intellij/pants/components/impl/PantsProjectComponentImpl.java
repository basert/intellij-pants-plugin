// Copyright 2015 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.components.impl;

import com.intellij.ProjectTopics;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.ide.impl.NewProjectUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.ModuleListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.twitter.intellij.pants.PantsBundle;
import com.twitter.intellij.pants.components.PantsProjectComponent;
import com.twitter.intellij.pants.execution.PantsMakeBeforeRun;
import com.twitter.intellij.pants.file.FileChangeTracker;
import com.twitter.intellij.pants.metrics.LivePantsMetrics;
import com.twitter.intellij.pants.metrics.PantsExternalMetricsListenerManager;
import com.twitter.intellij.pants.metrics.PantsMetrics;
import com.twitter.intellij.pants.model.PantsOptions;
import com.twitter.intellij.pants.service.project.PantsResolver;
import com.twitter.intellij.pants.settings.PantsProjectSettings;
import com.twitter.intellij.pants.settings.PantsSettings;
import com.twitter.intellij.pants.ui.PantsConsoleManager;
import com.twitter.intellij.pants.util.PantsConstants;
import com.twitter.intellij.pants.util.PantsUtil;
import icons.PantsIcons;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class PantsProjectComponentImpl extends AbstractProjectComponent implements PantsProjectComponent {
  protected PantsProjectComponentImpl(Project project) {
    super(project);
  }

  @Override
  public void projectClosed() {
    PantsMetrics.report();
    FileChangeTracker.unregisterProject(myProject);
    PantsConsoleManager.unregisterConsole(myProject);
    super.projectClosed();
  }

  @Override
  public void initComponent() {
    super.initComponent();
    LivePantsMetrics.registerDumbModeListener(myProject);
  }

  @Override
  public void disposeComponent() {
    super.disposeComponent();
    LivePantsMetrics.unregisterDumbModeListener(myProject);
  }

  @Override
  public void projectOpened() {
    PantsMetrics.initialize();
    PantsConsoleManager.registerConsole(myProject);
    if (PantsUtil.isPantsProject(myProject)) {
      addPantsProjectIgnoredDirs();
    }

    super.projectOpened();
    if (myProject.isDefault()) {
      return;
    }
    StartupManager.getInstance(myProject).registerPostStartupActivity(
      new Runnable() {
        @Override
        public void run() {
          /**
           * Set project to allow dynamic classpath for JUnit run. Still requires any junit run to specify dynamic classpath in
           * {@link com.twitter.intellij.pants.execution.PantsClasspathRunConfigurationExtension#updateJavaParameters}
           * IDEA's logic: {@link com.intellij.execution.configurations.CommandLineBuilder}
           */
          PropertiesComponent.getInstance(myProject).setValue("dynamic.classpath", true);

          if (PantsUtil.isSeedPantsProject(myProject)) {
            convertToPantsProject();
            addPantsProjectIgnoredDirs();
          }

          subscribeToRunConfigurationAddition();
          FileChangeTracker.registerProject(myProject);
          final AbstractExternalSystemSettings pantsSettings = ExternalSystemApiUtil.getSettings(myProject, PantsConstants.SYSTEM_ID);
          final boolean resolverVersionMismatch =
            pantsSettings instanceof PantsSettings && ((PantsSettings) pantsSettings).getResolverVersion() != PantsResolver.VERSION;
          if (resolverVersionMismatch && PantsUtil.isPantsProject(myProject)) {
            final int answer = Messages.showYesNoDialog(
              myProject,
              PantsBundle.message("pants.project.generated.with.old.version", myProject.getName()),
              PantsBundle.message("pants.name"),
              PantsIcons.Icon
            );
            if (answer == Messages.YES) {
              PantsUtil.refreshAllProjects(myProject);
            }
          }
        }

        /**
         * To convert a seed Pants project to a full bloom pants project:
         * 1. Obtain the targets and project_path generated by `pants idea-plugin` from
         * workspace file `project.iws` via `PropertiesComponent` API.
         * 2. Generate a refresh spec based on the info above.
         * 3. Explicitly call {@link PantsUtil#refreshAllProjects}.
         */
        private void convertToPantsProject() {
          PantsExternalMetricsListenerManager.getInstance().logIsGUIImport(false);
          String serializedTargets = PropertiesComponent.getInstance(myProject).getValue("targets");
          String projectPath = PropertiesComponent.getInstance(myProject).getValue("project_path");
          if (serializedTargets == null || projectPath == null) {
            return;
          }

          /**
           * Generate the import spec for the next refresh.
           */
          final List<String> targetSpecs = PantsUtil.gson.fromJson(serializedTargets, PantsUtil.TYPE_LIST_STRING);
          final boolean loadLibsAndSources = true;
          final boolean enableIncrementalImport = false;
          final boolean useIdeaProjectJdk = false;
          final PantsProjectSettings pantsProjectSettings =
            new PantsProjectSettings(targetSpecs, projectPath, loadLibsAndSources, enableIncrementalImport, useIdeaProjectJdk);

          /**
           * Following procedures in {@link com.intellij.openapi.externalSystem.util.ExternalSystemUtil#refreshProjects}:
           * Make sure the setting is injected into the project for refresh.
           */
          ExternalSystemManager<?, ?, ?, ?, ?> manager = ExternalSystemApiUtil.getManager(PantsConstants.SYSTEM_ID);
          if (manager == null) {
            return;
          }
          AbstractExternalSystemSettings settings = manager.getSettingsProvider().fun(myProject);
          settings.setLinkedProjectsSettings(Collections.singleton(pantsProjectSettings));
          PantsUtil.refreshAllProjects(myProject);

          prepareGuiComponents();

          myProject.getMessageBus().connect().subscribe(ProjectTopics.MODULES, new ModuleListener() {
            @Override
            public void moduleAdded(@NotNull Project project, @NotNull Module module) {
              applyProjectSdk();
            }
          });
        }

        /**
         * Ensure GUI is set correctly because empty IntelliJ project (seed project in this case)
         * does not have these set by default.
         * 1. Make sure the project view is opened so view switch will follow.
         * 2. Pants tool window is initialized; otherwise no message can be shown when invoking `PantsCompile`.
         */
        private void prepareGuiComponents() {
          if (!ApplicationManager.getApplication().isUnitTestMode()) {
            if (ToolWindowManager.getInstance(myProject).getToolWindow("Project") != null) {
              ToolWindowManager.getInstance(myProject).getToolWindow("Project").show(null);
            }
            ExternalSystemUtil.ensureToolWindowInitialized(myProject, PantsConstants.SYSTEM_ID);
          }
        }

        private void subscribeToRunConfigurationAddition() {
          myProject.getMessageBus().connect().subscribe(RunManagerListener.TOPIC, new RunManagerListener() {
            @Override
            public void runConfigurationAdded(@NotNull RunnerAndConfigurationSettings settings) {
              if (!PantsUtil.isPantsProject(myProject) && !PantsUtil.isSeedPantsProject(myProject)) {
                return;
              }
              PantsMakeBeforeRun.replaceDefaultMakeWithPantsMake(myProject, settings);
            }
          });
        }
      }
    );
  }

  /**
   * This will add buildroot/.idea, buildroot/.pants.d to Version Control -> Ignored Files.
   * This is currently impossible to test because {@link com.intellij.openapi.externalSystem.test.ExternalSystemTestCase}
   * put project file in a temp dir unrelated to where the repo resides.
   * TODO: make sure it reflects on GUI immediately without a project reload.
   */
  private void addPantsProjectIgnoredDirs() {
    PantsUtil.findBuildRoot(myProject).ifPresent(
      buildRoot -> {
        ChangeListManagerImpl clm = ChangeListManagerImpl.getInstanceImpl(myProject);

        String pathToIgnore = buildRoot.getPath() + File.separator + ".idea";
        clm.addDirectoryToIgnoreImplicitly(pathToIgnore);

        PantsOptions.getPantsOptions(myProject).map(optionObj -> optionObj.get(PantsConstants.PANTS_OPTION_PANTS_WORKDIR))
          .ifPresent(optionString -> optionString.ifPresent(
            clm::addDirectoryToIgnoreImplicitly
          ));
      }
    );
  }

  private void applyProjectSdk() {
    ApplicationManager.getApplication().runWriteAction(() -> {
      Optional<VirtualFile> pantsExecutable = PantsUtil.findPantsExecutable(myProject);
      if (!pantsExecutable.isPresent()) {
        return;
      }

      Optional<Sdk> sdk = PantsUtil.getDefaultJavaSdk(pantsExecutable.get().getPath(), null);
      if (!sdk.isPresent()) {
        return;
      }

      NewProjectUtil.applyJdkToProject(myProject, sdk.get());
    });
  }
}
