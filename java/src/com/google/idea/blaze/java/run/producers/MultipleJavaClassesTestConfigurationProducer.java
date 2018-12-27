/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.java.run.producers;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.BlazeFlags;
import com.google.idea.blaze.base.dependencies.TargetInfo;
import com.google.idea.blaze.base.dependencies.TestSize;
import com.google.idea.blaze.base.lang.buildfile.search.BlazePackage;
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration;
import com.google.idea.blaze.base.run.BlazeCommandRunConfigurationType;
import com.google.idea.blaze.base.run.BlazeConfigurationNameBuilder;
import com.google.idea.blaze.base.run.producers.BlazeRunConfigurationProducer;
import com.google.idea.blaze.base.run.smrunner.SmRunnerUtils;
import com.google.idea.blaze.base.run.state.BlazeCommandRunConfigurationCommonState;
import com.google.idea.blaze.java.run.RunUtil;
import com.intellij.execution.Location;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.junit.JUnitUtil;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Runs tests in all selected java classes (or all classes below selected directory). Ignores
 * classes spread across multiple test targets.
 */
public class MultipleJavaClassesTestConfigurationProducer
    extends BlazeRunConfigurationProducer<BlazeCommandRunConfiguration> {

  public MultipleJavaClassesTestConfigurationProducer() {
    super(BlazeCommandRunConfigurationType.getInstance());
  }

  @Override
  protected boolean restrictedToProjectFiles() {
    return true;
  }

  @Override
  protected boolean doSetupConfigFromContext(
      BlazeCommandRunConfiguration configuration,
      ConfigurationContext context,
      Ref<PsiElement> sourceElement) {
    TestLocation location = getTestLocation(context);
    if (location == null) {
      return false;
    }
    sourceElement.set(location.psiLocation);
    configuration.setTargetInfo(location.target);
    BlazeCommandRunConfigurationCommonState handlerState =
        configuration.getHandlerStateIfType(BlazeCommandRunConfigurationCommonState.class);
    if (handlerState == null) {
      return false;
    }
    handlerState.getCommandState().setCommand(BlazeCommandName.TEST);

    // remove old test filter flag if present
    List<String> flags = new ArrayList<>(handlerState.getBlazeFlagsState().getRawFlags());
    flags.removeIf((flag) -> flag.startsWith(BlazeFlags.TEST_FILTER));
    if (location.testFilter != null) {
      flags.add(location.testFilter);
    }
    handlerState.getBlazeFlagsState().setRawFlags(flags);

    if (location.description != null) {
      BlazeConfigurationNameBuilder nameBuilder = new BlazeConfigurationNameBuilder(configuration);
      nameBuilder.setTargetString(location.description);
      configuration.setName(nameBuilder.build());
      configuration.setNameChangedByUser(true); // don't revert to generated name
    } else {
      configuration.setGeneratedName();
    }
    return true;
  }

  @Override
  protected boolean doIsConfigFromContext(
      BlazeCommandRunConfiguration configuration, ConfigurationContext context) {

    TestLocation location = getTestLocation(context);
    if (location == null) {
      return false;
    }
    BlazeCommandRunConfigurationCommonState handlerState =
        configuration.getHandlerStateIfType(BlazeCommandRunConfigurationCommonState.class);
    if (handlerState == null) {
      return false;
    }
    return BlazeCommandName.TEST.equals(handlerState.getCommandState().getCommand())
        && location.target.label.equals(configuration.getTarget())
        && Objects.equals(location.testFilter, handlerState.getTestFilterFlag());
  }

  @Nullable
  private static TestLocation getTestLocation(ConfigurationContext context) {
    if (!SmRunnerUtils.getSelectedSmRunnerTreeElements(context).isEmpty()) {
      // handled by a different producer
      return null;
    }
    PsiElement location = context.getPsiLocation();
    if (location instanceof PsiDirectory) {
      PsiDirectory dir = (PsiDirectory) location;
      TargetInfo target = getTestTargetIfUnique(dir);
      return target != null ? TestLocation.fromDirectory(target, dir) : null;
    }
    Set<PsiClass> testClasses = selectedTestClasses(context);
    if (testClasses.size() < 2) {
      return null;
    }
    TargetInfo target = getTestTargetIfUnique(testClasses);
    if (target == null) {
      return null;
    }
    testClasses = ProducerUtils.includeInnerTestClasses(testClasses);
    return TestLocation.fromClasses(target, testClasses);
  }

  private static Set<PsiClass> selectedTestClasses(ConfigurationContext context) {
    DataContext dataContext = context.getDataContext();
    PsiElement[] elements = getSelectedPsiElements(dataContext);
    if (elements == null) {
      return ImmutableSet.of();
    }
    return Arrays.stream(elements)
        .map(JUnitUtil::getTestClass)
        .filter(Objects::nonNull)
        .filter(testClass -> !testClass.hasModifierProperty(PsiModifier.ABSTRACT))
        .collect(Collectors.toSet());
  }

  @Nullable
  private static PsiElement[] getSelectedPsiElements(DataContext context) {
    PsiElement[] elements = LangDataKeys.PSI_ELEMENT_ARRAY.getData(context);
    if (elements != null) {
      return elements;
    }
    PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(context);
    return element != null ? new PsiElement[] {element} : null;
  }

  @Nullable
  private static TargetInfo getTestTargetIfUnique(PsiDirectory directory) {
    ProjectFileIndex index = ProjectFileIndex.SERVICE.getInstance(directory.getProject());
    if (BlazePackage.hasBlazePackageChild(directory, dir -> relevantDirectory(index, dir))) {
      return null;
    }
    Set<PsiClass> classes = new HashSet<>();
    addClassesInDirectory(directory, classes);
    return getTestTargetIfUnique(classes);
  }

  private static boolean relevantDirectory(ProjectFileIndex index, PsiDirectory dir) {
    // only search under java source roots
    return index.isInSourceContent(dir.getVirtualFile());
  }

  private static void addClassesInDirectory(PsiDirectory directory, Set<PsiClass> list) {
    Collections.addAll(list, JavaDirectoryService.getInstance().getClasses(directory));
    for (PsiDirectory child : directory.getSubdirectories()) {
      addClassesInDirectory(child, list);
    }
  }

  @Nullable
  private static TargetInfo getTestTargetIfUnique(Set<PsiClass> classes) {
    TargetInfo testTarget = null;
    for (PsiClass psiClass : classes) {
      TargetInfo target = testTargetForClass(psiClass);
      if (target == null) {
        continue;
      }
      if (testTarget != null && !testTarget.equals(target)) {
        return null;
      }
      testTarget = target;
    }
    return testTarget;
  }

  @Nullable
  private static TargetInfo testTargetForClass(PsiClass psiClass) {
    PsiClass testClass = JUnitUtil.getTestClass(psiClass);
    if (testClass == null || testClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
      return null;
    }
    TestSize testSize = TestSizeFinder.getTestSize(psiClass);
    return RunUtil.targetForTestClass(psiClass, testSize);
  }

  private static class TestLocation {
    @Nullable
    static TestLocation fromClasses(TargetInfo target, Set<PsiClass> classes) {
      Map<PsiClass, Collection<Location<?>>> methodsPerClass =
          classes.stream().collect(Collectors.toMap(c -> c, c -> ImmutableList.of()));
      String filter = BlazeJUnitTestFilterFlags.testFilterForClassesAndMethods(methodsPerClass);
      if (filter == null) {
        return null;
      }
      PsiClass sampleClass =
          classes.stream().min(Comparator.comparing(PsiClass::getName)).orElse(null);
      String name = sampleClass.getName();
      if (classes.size() > 1) {
        name += String.format(" and %s others", classes.size() - 1);
      }
      return new TestLocation(target, sampleClass, filter, name);
    }

    @Nullable
    static TestLocation fromDirectory(TargetInfo target, PsiDirectory dir) {
      String packagePrefix =
          ProjectFileIndex.SERVICE
              .getInstance(dir.getProject())
              .getPackageNameByDirectory(dir.getVirtualFile());
      if (packagePrefix == null) {
        return null;
      }
      String description =
          packagePrefix.isEmpty() ? null : String.format("all in directory '%s'", dir.getName());
      return new TestLocation(target, dir, packagePrefix, description);
    }

    private final TargetInfo target;
    private final PsiElement psiLocation;
    @Nullable private final String testFilter;
    @Nullable private final String description;

    private TestLocation(
        TargetInfo target,
        PsiElement psiLocation,
        String testFilter,
        @Nullable String description) {
      this.target = target;
      this.psiLocation = psiLocation;
      this.testFilter = !testFilter.isEmpty() ? BlazeFlags.TEST_FILTER + "=" + testFilter : null;
      this.description = description;
    }
  }
}
