package com.intellij.lang.javascript.flex.flashbuilder;

import com.intellij.ide.highlighter.ModuleFileType;
import com.intellij.ide.util.projectWizard.ModuleBuilder;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.flex.FlexModuleBuilder;
import com.intellij.lang.javascript.flex.FlexModuleType;
import com.intellij.lang.javascript.flex.projectStructure.FlexIdeBCConfigurator;
import com.intellij.lang.javascript.flex.projectStructure.FlexIdeBuildConfigurationsExtension;
import com.intellij.lang.javascript.flex.projectStructure.model.impl.FlexProjectConfigurationEditor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.*;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.impl.libraries.ApplicationLibraryTable;
import com.intellij.openapi.roots.impl.libraries.LibraryTableBase;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.ModifiableArtifactModel;
import com.intellij.projectImport.ProjectImportBuilder;
import com.intellij.util.PathUtil;
import com.intellij.util.PlatformUtils;
import com.intellij.util.io.ZipUtil;
import gnu.trove.THashMap;
import gnu.trove.THashSet;

import javax.swing.*;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.*;

public class FlashBuilderImporter extends ProjectImportBuilder<String> {

  public static final String DOT_PROJECT = ".project";
  public static final String DOT_FXP = ".fxp";
  public static final String DOT_FXPL = ".fxpl";
  public static final String DOT_ACTION_SCRIPT_PROPERTIES = ".actionScriptProperties";
  public static final String DOT_FXP_PROPERTIES = ".fxpProperties";
  public static final String DOT_FLEX_PROPERTIES = ".flexProperties";
  public static final String DOT_FLEX_LIB_PROPERTIES = ".flexLibProperties";

  private static final Icon flashBuilderIcon = IconLoader.getIcon("flash_builder.png", FlashBuilderImporter.class);

  private Parameters myParameters;

  public static class Parameters {
    private String myInitiallySelectedPath = "";
    private List<String> myFlashBuilderProjectFilePaths = Collections.emptyList();
    private String myFxpExtractPath = "";
    private boolean myExtractToSubfolder = false;
    private boolean myOpenProjectSettingsAfter = false;
  }

  public Parameters getParameters() {
    if (myParameters == null) {
      myParameters = new Parameters();
    }
    return myParameters;
  }

  public void cleanup() {
    super.cleanup();
    myParameters = null;
  }

  public String getName() {
    return FlexBundle.message("flash.builder");
  }

  public Icon getIcon() {
    return flashBuilderIcon;
  }

  public boolean isMarked(final String element) {
    return true;
  }

  public boolean isOpenProjectSettingsAfter() {
    return getParameters().myOpenProjectSettingsAfter;
  }

  public void setOpenProjectSettingsAfter(boolean openProjectSettingsAfter) {
    getParameters().myOpenProjectSettingsAfter = openProjectSettingsAfter;
  }

  public boolean isExtractToSubfolder() {
    return getParameters().myExtractToSubfolder;
  }

  public void setExtractToSubfolder(boolean extractToSubfolder) {
    getParameters().myExtractToSubfolder = extractToSubfolder;
  }

  public String getFxpExtractPath() {
    return getParameters().myFxpExtractPath;
  }

  public void setFxpExtractPath(String fxpExtractPath) {
    getParameters().myFxpExtractPath = fxpExtractPath;
  }

  public List<String> getList() {
    return getParameters().myFlashBuilderProjectFilePaths;
  }

  public void setList(final List<String> flashBuilderProjectFiles) /*throws ConfigurationException*/ {
    getParameters().myFlashBuilderProjectFilePaths = flashBuilderProjectFiles;
  }

  void setInitiallySelectedPath(final String dirPath) {
    getParameters().myInitiallySelectedPath = dirPath;
  }

  String getInitiallySelectedPath() {
    return getParameters().myInitiallySelectedPath;
  }

  public String getSuggestedProjectName() {
    final String path = getInitiallySelectedPath();
    VirtualFile file = path.isEmpty() ? null : LocalFileSystem.getInstance().findFileByPath(path);

    if (file == null) {
      return PathUtil.getFileName(path);
    }

    if (FlashBuilderProjectFinder.isArchivedFBProject(path)) {
      return file.getNameWithoutExtension();
    }

    if (file.isDirectory()) {
      final VirtualFile dotProjectFile = file.findChild(DOT_PROJECT);
      if (dotProjectFile != null && FlashBuilderProjectFinder.isFlashBuilderProject(dotProjectFile)) {
        file = dotProjectFile;
      }
    }

    if (DOT_PROJECT.equalsIgnoreCase(file.getName())) {
      return FlashBuilderProjectLoadUtil.readProjectName(file.getPath());
    }

    return PathUtil.getFileName(path);
  }

  public List<Module> commit(final Project project,
                             final ModifiableModuleModel model,
                             final ModulesProvider modulesProvider,
                             final ModifiableArtifactModel artifactModel) {
    FlexModuleBuilder.setupResourceFilePatterns(project);

    final boolean needToCommitModuleModel = model == null;
    final ModifiableModuleModel moduleModel = model != null ? model : ModuleManager.getInstance(project).getModifiableModel();

    final List<String> paths = getList();
    final boolean isFxp = paths.size() == 1 && FlashBuilderProjectFinder.isArchivedFBProject(paths.get(0));
    final List<String> dotProjectPaths = getDotProjectPaths(project);
    final Collection<FlashBuilderProject> flashBuilderProjects = FlashBuilderProjectLoadUtil.loadProjects(dotProjectPaths, isFxp);

    final ModuleType moduleType = PlatformUtils.isFlexIde() ? FlexModuleType.getInstance() : StdModuleTypes.JAVA;

    final Map<FlashBuilderProject, ModifiableRootModel> flashBuilderProjectToModifiableModelMap =
      new THashMap<FlashBuilderProject, ModifiableRootModel>();
    final Map<Module, ModifiableRootModel> moduleToModifiableModelMap = new THashMap<Module, ModifiableRootModel>();
    final Set<String> moduleNames = new THashSet<String>(flashBuilderProjects.size());

    final FlexProjectConfigurationEditor currentFlexEditor =
      PlatformUtils.isFlexIde() ? FlexIdeBuildConfigurationsExtension.getInstance().getConfigurator().getConfigEditor() : null;

    for (FlashBuilderProject flashBuilderProject : flashBuilderProjects) {
      final String moduleName = makeUnique(flashBuilderProject.getName(), moduleNames);
      moduleNames.add(moduleName);

      final String moduleFilePath = flashBuilderProject.getProjectRootPath() + "/" + moduleName + ModuleFileType.DOT_DEFAULT_EXTENSION;

      if (LocalFileSystem.getInstance().findFileByPath(moduleFilePath) != null) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          public void run() {
            ModuleBuilder.deleteModuleFile(moduleFilePath);
          }
        });
      }

      final Module module = moduleModel.newModule(moduleFilePath, moduleType);
      final ModifiableRootModel rootModel;
      if (PlatformUtils.isFlexIde() && currentFlexEditor != null) {
        rootModel = currentFlexEditor.getModifiableRootModel(module);
      }
      else {
        rootModel = ModuleRootManager.getInstance(module).getModifiableModel();
      }

      flashBuilderProjectToModifiableModelMap.put(flashBuilderProject, rootModel);
      moduleToModifiableModelMap.put(module, rootModel);
    }


    final LibraryTableBase.ModifiableModelEx globalLibrariesModifiableModel;
    final FlexProjectConfigurationEditor flexConfigEditor;
    final boolean needToCommitFlexEditor;
    final boolean needToCommitRootModels;

    if (!PlatformUtils.isFlexIde()) {
      globalLibrariesModifiableModel = null;
      flexConfigEditor = null;
      needToCommitFlexEditor = false;
      needToCommitRootModels = true;
    }
    else if (currentFlexEditor != null) {
      globalLibrariesModifiableModel = null;
      flexConfigEditor = currentFlexEditor;
      needToCommitFlexEditor = false;
      needToCommitRootModels = false;
    }
    else {
      globalLibrariesModifiableModel =
        (LibraryTableBase.ModifiableModelEx)ApplicationLibraryTable.getApplicationTable().getModifiableModel();
      flexConfigEditor = createFlexConfigEditor(project, moduleToModifiableModelMap, globalLibrariesModifiableModel);
      needToCommitFlexEditor = true;
      needToCommitRootModels = true;
    }

    if (needToCommitModuleModel) {
      assert needToCommitRootModels;
    }

    final FlashBuilderSdkFinder sdkFinder =
      new FlashBuilderSdkFinder(project, flexConfigEditor, getParameters().myInitiallySelectedPath, flashBuilderProjects);

    final FlashBuilderModuleImporter flashBuilderModuleImporter =
      new FlashBuilderModuleImporter(project, flexConfigEditor, flashBuilderProjects, sdkFinder);

    for (final FlashBuilderProject flashBuilderProject : flashBuilderProjects) {
      flashBuilderModuleImporter.setupModule(flashBuilderProjectToModifiableModelMap.get(flashBuilderProject), flashBuilderProject);
    }

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        if (PlatformUtils.isFlexIde()) {
          if (globalLibrariesModifiableModel != null) {
            globalLibrariesModifiableModel.commit();
          }

          if (needToCommitFlexEditor) {
            try {
              flexConfigEditor.commit();
            }
            catch (ConfigurationException e) {
              Logger.getInstance(FlashBuilderImporter.class).error(e);
            }
          }
        }

        final Collection<ModifiableRootModel> rootModels = moduleToModifiableModelMap.values();
        if (needToCommitModuleModel) {
          ProjectRootManager.getInstance(project).multiCommit(moduleModel, rootModels.toArray(new ModifiableRootModel[rootModels.size()]));
        }
        else if (needToCommitRootModels) {
          for (ModifiableRootModel rootModel : rootModels) {
            rootModel.commit();
          }
        }
      }
    });

    return new ArrayList<Module>(moduleToModifiableModelMap.keySet());
  }

  private List<String> getDotProjectPaths(final Project project) {
    final boolean creatingNewProject = !isUpdate();
    final List<String> paths = getList();

    if (paths.size() == 1 && FlashBuilderProjectFinder.isArchivedFBProject(paths.get(0))) {
      final List<String> dotProjectFiles = new ArrayList<String>();
      final boolean multipleProjects = FlashBuilderProjectFinder.isMultiProjectFxp(paths.get(0));

      final String basePath = creatingNewProject ? project.getBaseDir().getPath() : getFxpExtractPath();
      assert basePath != null;
      final String fxpExtractDir = multipleProjects || isExtractToSubfolder()
                                   ? basePath + "/" + FileUtil.getNameWithoutExtension(PathUtil.getFileName(paths.get(0)))
                                   : basePath;

      try {
        final File outputDir = new File(fxpExtractDir);
        ZipUtil.extract(new File(paths.get(0)), outputDir, null);
        dotProjectFiles.add(fxpExtractDir + "/" + DOT_PROJECT);

        extractNestedFxpAndAppendProjects(outputDir, dotProjectFiles);

        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          public void run() {
            for (String dotProjectFile : dotProjectFiles) {
              final VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(PathUtil.getParentPath(dotProjectFile));
              if (file != null) {
                file.refresh(false, true);
              }
            }
          }
        });
      }
      catch (IOException e) {
        Messages.showErrorDialog(project, FlexBundle.message("failed.to.extract.project", e.getMessage()),
                                 FlexBundle.message("open.project.0", PathUtil.getFileName(paths.get(0))));
        return Collections.emptyList();
      }

      return dotProjectFiles;
    }

    return paths;
  }

  private static void extractNestedFxpAndAppendProjects(final File dir, final List<String> dotProjectFiles) throws IOException {
    final FilenameFilter filter = new FilenameFilter() {
      public boolean accept(final File dir, final String name) {
        final String lowercased = name.toLowerCase();
        return lowercased.endsWith(DOT_FXP) || lowercased.endsWith(DOT_FXPL);
      }
    };

    for (File file : dir.listFiles(filter)) {
      final File extractDir = new File(file.getParentFile().getParentFile(), FileUtil.getNameWithoutExtension(file.getName()));
      ZipUtil.extract(file, extractDir, null);
      dotProjectFiles.add(extractDir + "/" + DOT_PROJECT);

      extractNestedFxpAndAppendProjects(extractDir, dotProjectFiles);
    }
  }

  private static String makeUnique(final String name, final Set<String> moduleNames) {
    String uniqueName = name;
    int i = 1;
    while (moduleNames.contains(uniqueName)) {
      uniqueName = name + '(' + i++ + ')';
    }
    return uniqueName;
  }

  private static FlexProjectConfigurationEditor createFlexConfigEditor(final Project project,
                                                                       final Map<Module, ModifiableRootModel> moduleToModifiableModelMap,
                                                                       final LibraryTableBase.ModifiableModelEx globalLibrariesModifiableModel) {
    final FlexProjectConfigurationEditor.ProjectModifiableModelProvider provider =
      new FlexProjectConfigurationEditor.ProjectModifiableModelProvider() {
        public Module[] getModules() {
          final Set<Module> modules = moduleToModifiableModelMap.keySet();
          return modules.toArray(new Module[modules.size()]);
        }

        public ModifiableRootModel getModuleModifiableModel(final Module module) {
          return moduleToModifiableModelMap.get(module);
        }

        public void addListener(final FlexIdeBCConfigurator.Listener listener,
                                final Disposable parentDisposable) {
          // modules and BCs are not removed here
        }

        public void commitModifiableModels() throws ConfigurationException {
          // commit will be performed outside of #setupRootModel()
        }

        public LibraryTableBase.ModifiableModelEx getLibrariesModifiableModel(final String level) {
          if (LibraryTablesRegistrar.APPLICATION_LEVEL.equals(level)) {
            return globalLibrariesModifiableModel;
          }
          else {
            throw new UnsupportedOperationException();
          }
        }
      };

    return new FlexProjectConfigurationEditor(project, provider);
  }
}
