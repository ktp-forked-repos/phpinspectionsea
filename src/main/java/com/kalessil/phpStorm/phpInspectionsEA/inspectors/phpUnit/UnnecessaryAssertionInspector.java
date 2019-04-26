package com.kalessil.phpStorm.phpInspectionsEA.inspectors.phpUnit;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.jetbrains.php.config.PhpLanguageFeature;
import com.jetbrains.php.config.PhpLanguageLevel;
import com.jetbrains.php.config.PhpProjectConfigurationFacade;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.GenericPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiElementsUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiResolveUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.PossibleValuesDiscoveryUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.Types;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class UnnecessaryAssertionInspector extends BasePhpInspection {
    private final static String messageReturnType = "This assertion can probably be skipped (argument implicitly declares return type).";
    private final static String messageExpectsAny = "This assertion can probably be omitted ('->expects(...->any())' to be more specific).";

    final private static Map<String, Integer> targetPositions = new HashMap<>();
    final private static Map<String, String> targetType       = new HashMap<>();
    static {
        targetPositions.put("assertInstanceOf",   1);
        targetPositions.put("assertEmpty",        0);
        targetPositions.put("assertNull",         0);
        targetPositions.put("assertInternalType", 1);

        targetType.put("assertEmpty",        Types.strVoid);
        targetType.put("assertNull",         Types.strVoid);
        targetType.put("assertInstanceOf",   null);
        targetType.put("assertInternalType", null);
    }

    @NotNull
    public String getShortName() {
        return "UnnecessaryAssertionInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new GenericPhpElementVisitor() {
            @Override
            public void visitPhpMethodReference(@NotNull MethodReference reference) {
                if (this.shouldSkipAnalysis(reference, StrictnessCategory.STRICTNESS_CATEGORY_PHPUNIT)) { return; }

                final String methodName = reference.getName();
                if (methodName != null) {
                    if (methodName.startsWith("assert")) {
                        this.analyzeTypeHintCase(reference, methodName);
                    } else {
                        this.analyzeMockingAsserts(reference, methodName);
                    }
                }
            }

            private void analyzeMockingAsserts(@NotNull MethodReference reference, @NotNull String methodName) {
                if (methodName.equals("expects")) {
                    final PsiElement[] arguments = reference.getParameters();
                    if (arguments.length == 1 && arguments[0] instanceof MethodReference) {
                        final MethodReference innerReference = (MethodReference) arguments[0];
                        final String innerMethodName         = innerReference.getName();
                        if (innerMethodName != null && innerMethodName.equals("any")) {
                            holder.registerProblem(
                                    innerReference,
                                    messageExpectsAny,
                                    new RemoveExpectsAssertionFixer(reference, reference.getFirstChild())
                            );
                        }
                    }
                }
            }

            private void analyzeTypeHintCase(@NotNull MethodReference reference, @NotNull String methodName) {
                final Project project      = holder.getProject();
                final PhpLanguageLevel php = PhpProjectConfigurationFacade.getInstance(project).getLanguageLevel();
                if (php.hasFeature(PhpLanguageFeature.RETURN_TYPES) && targetPositions.containsKey(methodName)) {
                    final int position           = targetPositions.get(methodName);
                    final PsiElement[] arguments = reference.getParameters();
                    if (arguments.length >= position + 1) {
                        final Set<PsiElement> values = PossibleValuesDiscoveryUtil.discover(arguments[position]);
                        if (values.size() == 1) {
                            final PsiElement candidate = values.iterator().next();
                            if (candidate instanceof FunctionReference) {
                                final FunctionReference call = (FunctionReference) candidate;
                                final PsiElement function    = OpenapiResolveUtil.resolveReference(call);
                                if (function instanceof Function) {
                                    final PsiElement returnType = OpenapiElementsUtil.getReturnType((Function) function);
                                    if (returnType != null) {
                                        final PhpType resolved = OpenapiResolveUtil.resolveType(call, project);
                                        if (resolved != null && resolved.size() == 1 && !resolved.hasUnknown()) {
                                            /* find out what is expected */
                                            String expectedType = targetType.get(methodName);
                                            if (methodName.equals("assertInstanceOf")) {
                                                if (arguments[0] instanceof ClassConstantReference) {
                                                    final ClassConstantReference expectation = (ClassConstantReference) arguments[0];
                                                    final PsiElement base                    = expectation.getClassReference();
                                                    if (base instanceof ClassReference) {
                                                        final PsiElement clazz = OpenapiResolveUtil.resolveReference((ClassReference) base);
                                                        if (clazz instanceof PhpClass) {
                                                            expectedType = ((PhpClass) clazz).getFQN();
                                                        }
                                                    }
                                                }
                                            }
                                            /* match arguments types */
                                            final String expected = expectedType;
                                            if (expected == null) {
                                                holder.registerProblem(reference, messageReturnType);
                                            } else if (resolved.getTypes().stream().anyMatch(t -> Types.getType(t).equals(expected))) {
                                                holder.registerProblem(reference, messageReturnType);
                                            }

                                        }
                                    }
                                }
                            }
                        }
                        values.clear();
                    }
                }
            }
        };
    }

    private final static class RemoveExpectsAssertionFixer implements LocalQuickFix {
        private static final String title = "Remove the expects-assertion";
        private final SmartPsiElementPointer<PsiElement> assertion;
        private final SmartPsiElementPointer<PsiElement> base;

        @NotNull
        @Override
        public String getName() {
            return title;
        }

        @NotNull
        @Override
        public String getFamilyName() {
            return title;
        }

        RemoveExpectsAssertionFixer(@NotNull PsiElement assertion, @NotNull PsiElement base) {
            super();
            final SmartPointerManager factory = SmartPointerManager.getInstance(assertion.getProject());
            this.assertion                    = factory.createSmartPsiElementPointer(assertion);
            this.base                         = factory.createSmartPsiElementPointer(base);
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            final PsiElement assertion = this.assertion.getElement();
            final PsiElement base      = this.base.getElement();
            if (assertion != null && base != null && !project.isDisposed()) {
                assertion.replace(base);
            }
        }
    }
}
