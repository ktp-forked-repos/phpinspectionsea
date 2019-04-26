package com.kalessil.phpStorm.phpInspectionsEA.inspectors.languageConstructions;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.PhpPsiElementFactory;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.GenericPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiPsiSearchUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiResolveUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class DynamicInvocationViaScopeResolutionInspector extends BasePhpInspection {
    private static final String patternScopeResolutionUsed = "'$this->%s(...)' should be used instead.";
    private static final String patternExpressionUsed      = "'...->%s(...)' should be used instead.";

    @NotNull
    public String getShortName() {
        return "DynamicInvocationViaScopeResolutionInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new GenericPhpElementVisitor() {
            @Override
            public void visitPhpMethodReference(@NotNull MethodReference reference) {
                if (this.shouldSkipAnalysis(reference, StrictnessCategory.STRICTNESS_CATEGORY_CODE_STYLE)) { return; }

                final PsiElement operator = OpenapiPsiSearchUtil.findResolutionOperator(reference);
                if (!OpenapiTypesUtil.is(operator, PhpTokenTypes.SCOPE_RESOLUTION)) {
                    return;
                }

                final PsiReference classReference = reference.getReference();
                final String methodName         = reference.getName();
                if (classReference != null && methodName != null && !methodName.isEmpty()) {
                    final PsiElement resoled = OpenapiResolveUtil.resolveReference(classReference);
                    /* resolved method is static but called with $this */
                    if (resoled instanceof Method) {
                        final Method method  = (Method) resoled;
                        final PhpClass clazz = method.getContainingClass();
                        if (clazz != null && !clazz.isInterface() && !method.isStatic() && !method.isAbstract()) {
                            /* check first pattern [static|self|Clazz]::dynamic */
                            final PsiElement staticCandidate = reference.getFirstChild();
                            final String candidateContent    = staticCandidate.getText();
                            if (candidateContent.equals("static") || candidateContent.equals("self") || candidateContent.equals(clazz.getName())) {
                                final Function scope = ExpressionSemanticUtil.getScope(reference);
                                if (scope instanceof Method && !methodName.equalsIgnoreCase(scope.getName())) {
                                    if (((Method) scope).isStatic()) {
                                        holder.registerProblem(
                                                reference,
                                                String.format(patternExpressionUsed, reference.getName())
                                        );
                                    } else {
                                        holder.registerProblem(
                                                reference,
                                                String.format(patternScopeResolutionUsed, methodName),
                                                new TheLocalFix(operator, staticCandidate)
                                        );
                                    }
                                }
                                return;
                            }

                            /* check second pattern <expression>::dynamic */
                            final PsiElement base = reference.getFirstPsiChild();
                            if (base != null && !(base instanceof FunctionReference) && !(staticCandidate instanceof ClassReference)) {
                                holder.registerProblem(
                                        reference,
                                        String.format(patternExpressionUsed, reference.getName()),
                                        new TheLocalFix(operator, null)
                                );
                            }
                        }
                    }
                }
            }
        };
    }

    private static final class TheLocalFix implements LocalQuickFix {
        private static final String title = "Use -> instead";

        private final SmartPsiElementPointer<PsiElement> base;
        private final SmartPsiElementPointer<PsiElement> operator;

        TheLocalFix(@NotNull PsiElement operator, @Nullable PsiElement base) {
            super();
            final SmartPointerManager factory = SmartPointerManager.getInstance(operator.getProject());

            this.base     = base == null ? null : factory.createSmartPsiElementPointer(base);
            this.operator = factory.createSmartPsiElementPointer(operator);
        }

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

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            final PsiElement operator = this.operator.getElement();
            if (operator != null && !project.isDisposed()) {
                operator.replace(PhpPsiElementFactory.createArrow(project));

                final PsiElement base = this.base == null ? null : this.base.getElement();
                if (base != null) {
                    base.replace(PhpPsiElementFactory.createVariable(project, "this", true));
                }
            }
        }
    }
}