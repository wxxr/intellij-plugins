/*
 * Copyright 2008 The authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.struts2;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.RunResult;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.struts2.facet.StrutsFacet;
import com.intellij.struts2.facet.StrutsFacetType;
import com.intellij.struts2.facet.ui.StrutsFileSet;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.*;
import org.jetbrains.annotations.NonNls;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * Base class for highlighting tests.
 *
 * @author Yann C&eacute;bron
 */
public abstract class BasicHighlightingTestCase<T extends JavaModuleFixtureBuilder> extends BasicStrutsTestCase {

  protected CodeInsightTestFixture myFixture;
  protected ModuleFixture myModuleTestFixture;
  protected Project myProject;
  protected Module myModule;
  protected StrutsFacet myFacet;

  protected Class<T> getModuleFixtureBuilderClass() {
    //noinspection unchecked
    return (Class<T>) JavaModuleFixtureBuilder.class;
  }

  /**
   * Inspections to run for highlighting tests.
   *
   * @return Inspection tools.
   */
  protected abstract LocalInspectionTool[] getHighlightingInspections();

  protected void setUp() throws Exception {
    super.setUp();

    final TestFixtureBuilder<IdeaProjectTestFixture> projectBuilder =
        IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder();
    final T moduleBuilder = projectBuilder.addModule(getModuleFixtureBuilderClass());
    myFixture = IdeaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(projectBuilder.getFixture());

    myFixture.setTestDataPath(getTestDataPath());

    configureModule(moduleBuilder);
    myFixture.enableInspections(getHighlightingInspections());

    myFixture.setUp();

    myProject = myFixture.getProject();
    myModuleTestFixture = moduleBuilder.getFixture();
    myModule = myModuleTestFixture.getModule();
    myFacet = createFacet();
  }

  protected void configureModule(final T moduleBuilder) throws Exception {
    moduleBuilder.addContentRoot(myFixture.getTempDirPath());
    moduleBuilder.addContentRoot(getTestDataPath());
    moduleBuilder.addSourceRoot("src");
    addStrutsJars(moduleBuilder);
  }

  /**
   * Adds the S2 jars.
   *
   * @param moduleBuilder Current module builder.
   * @throws Exception On internal errors.
   */
  protected final void addStrutsJars(final T moduleBuilder) throws Exception {
    addLibrary(moduleBuilder, "struts2",
               "struts2-core-2.0.12.jar",
               "freemarker-2.3.10.jar",
               "ognl-2.6.11.jar",
               "xwork-2.0.6.jar");
  }

  protected void addLibrary(final T moduleBuilder, @NonNls final String libraryName, @NonNls final String... jarPaths) {
    final File testDataBasePathFile = new File(getTestDataBasePath()); // little hack to get absolute path..
    moduleBuilder.addLibraryJars(libraryName,
                                 testDataBasePathFile.getAbsolutePath(),
                                 jarPaths);
  }

  protected final StrutsFacet createFacet() {
    final RunResult<StrutsFacet> runResult = new WriteCommandAction<StrutsFacet>(myProject) {
      protected void run(final Result<StrutsFacet> result) throws Throwable {
        String name = StrutsFacetType.INSTANCE.getPresentableName();
        final StrutsFacet facet = FacetManager.getInstance(myModule).addFacet(StrutsFacetType.INSTANCE, name, null);
        result.setResult(facet);
      }
    }.execute();
    final Throwable throwable = runResult.getThrowable();
    if (throwable != null) {
      throw new RuntimeException("error setting up StrutsFacet", throwable);
    }

    return runResult.getResultObject();
  }

  protected void tearDown() throws Exception {
    myFixture.tearDown();
    myFixture = null;
    myModuleTestFixture = null;
    myProject = null;
    myModule = null;
    myFacet = null;
    super.tearDown();
  }

  private void addToFileSet(final StrutsFileSet fileSet, @NonNls final String path) {
    final VirtualFile file;
    try {
      file = myFixture.copyFileToProject(path);
    }
    catch (IOException e) {
      throw new RuntimeException("error copying struts.xml from '" + path + "'", e);
    }
    assertNotNull("could not find file: '" + path + "'", file);
    fileSet.addFile(file);
  }

  protected void createStrutsFileSet(@NonNls final String... fileNames) {
    final StrutsFileSet fileSet = new StrutsFileSet("test", "test");
    for (final String fileName : fileNames) {
      addToFileSet(fileSet, fileName);
    }
    final Set<StrutsFileSet> strutsFileSetSet = myFacet.getConfiguration().getFileSets();
    strutsFileSetSet.clear();
    strutsFileSetSet.add(fileSet);
  }

}