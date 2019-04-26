package com.kalessil.phpStorm.phpInspectionsEA.inspectors.apiUsage;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.FunctionReference;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.GenericPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import org.jetbrains.annotations.NotNull;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class ObGetCleanCanBeUsedInspector extends BasePhpInspection {
    private static final String message = "'ob_get_clean()' can be used instead.";

    @NotNull
    public String getShortName() {
        return "ObGetCleanCanBeUsedInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new GenericPhpElementVisitor() {
            @Override
            public void visitPhpFunctionCall(@NotNull FunctionReference reference) {
                if (this.shouldSkipAnalysis(reference, StrictnessCategory.STRICTNESS_CATEGORY_CONTROL_FLOW)) { return; }

                final String functionName = reference.getName();
                if (functionName != null && functionName.equals("ob_end_clean")) {
                    final PsiElement parent = reference.getParent();
                    if (OpenapiTypesUtil.isStatementImpl(parent)) {
                        final PsiElement previous = ((PhpPsiElement) parent).getPrevPsiSibling();
                        if (OpenapiTypesUtil.isStatementImpl(previous)) {
                            for (final FunctionReference call : PsiTreeUtil.findChildrenOfType(previous, FunctionReference.class)) {
                                if (OpenapiTypesUtil.isFunctionReference(call)) {
                                    final String callName = call.getName();
                                    if (callName != null && callName.equals("ob_get_contents")) {
                                        if (this.isFromRootNamespace(reference) && this.isFromRootNamespace(call)) {
                                            holder.registerProblem(call, message, new SimplifyFixer(parent));
                                        }
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        };
    }

    private static final class SimplifyFixer implements LocalQuickFix {
        private static final String title = "Use 'ob_get_clean()' instead";

        private final SmartPsiElementPointer<PsiElement> drop;

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

        SimplifyFixer(@NotNull PsiElement drop) {
            super();
            final SmartPointerManager factory = SmartPointerManager.getInstance(drop.getProject());
            this.drop                         = factory.createSmartPsiElementPointer(drop);
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            final PsiElement reference = descriptor.getPsiElement();
            if (reference instanceof FunctionReference && !project.isDisposed()) {
                ((FunctionReference) reference).handleElementRename("ob_get_clean");

                final PsiElement drop = this.drop.getElement();
                if (drop != null) {
                    drop.delete();
                }
            }
        }
    }
}