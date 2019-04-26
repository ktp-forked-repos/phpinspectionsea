package com.kalessil.phpStorm.phpInspectionsEA.inspectors.languageConstructions;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jetbrains.php.config.PhpLanguageLevel;
import com.jetbrains.php.config.PhpProjectConfigurationFacade;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;
import com.jetbrains.php.lang.psi.elements.ForeachStatement;
import com.jetbrains.php.lang.psi.elements.MultiassignmentExpression;
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

public class UnsupportedEmptyListAssignmentsInspector extends BasePhpInspection {
    private static final String message = "Provokes a PHP Fatal error (Cannot use empty list).";

    @NotNull
    public String getShortName() {
        return "UnsupportedEmptyListAssignmentsInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new GenericPhpElementVisitor() {
            @Override
            public void visitPhpForeach(@NotNull ForeachStatement expression) {
                if (this.shouldSkipAnalysis(expression, StrictnessCategory.STRICTNESS_CATEGORY_LANGUAGE_LEVEL_MIGRATION)) { return; }

                final PhpLanguageLevel php = PhpProjectConfigurationFacade.getInstance(holder.getProject()).getLanguageLevel();
                if (php.compareTo(PhpLanguageLevel.PHP700) >= 0 && expression.getVariables().isEmpty()) {
                    final PsiElement first = expression.getFirstChild();
                    if (first != null) {
                        boolean reachedAsKeyword = false;
                        boolean isTarget         = false;
                        PsiElement current       = first.getNextSibling();
                        while (current != null) {
                            if (!reachedAsKeyword) {
                                reachedAsKeyword = OpenapiTypesUtil.is(current, PhpTokenTypes.kwAS);
                            } else {
                                isTarget = OpenapiTypesUtil.is(current, PhpTokenTypes.kwLIST) ||
                                           OpenapiTypesUtil.is(current, PhpTokenTypes.chLBRACKET);
                                if (isTarget) {
                                    break;
                                }
                            }
                            current = current.getNextSibling();
                        }
                        if (isTarget) {
                            holder.registerProblem(current, message, ProblemHighlightType.GENERIC_ERROR);
                        }
                    }
                }
            }

            @Override
            public void visitPhpMultiassignmentExpression(@NotNull MultiassignmentExpression expression) {
                if (this.shouldSkipAnalysis(expression, StrictnessCategory.STRICTNESS_CATEGORY_LANGUAGE_LEVEL_MIGRATION)) { return; }

                final PhpLanguageLevel php = PhpProjectConfigurationFacade.getInstance(holder.getProject()).getLanguageLevel();
                if (php.compareTo(PhpLanguageLevel.PHP700) >= 0 && expression.getVariables().isEmpty()) {
                    final PsiElement first = expression.getFirstChild();
                    if (first != null) {
                        final boolean isTarget =
                                first instanceof ArrayCreationExpression ||
                                OpenapiTypesUtil.is(first, PhpTokenTypes.kwLIST) ||
                                OpenapiTypesUtil.is(first, PhpTokenTypes.chLBRACKET);
                        if (isTarget) {
                            holder.registerProblem(first, message, ProblemHighlightType.GENERIC_ERROR);
                        }
                    }
                }
            }
        };
    }
}
