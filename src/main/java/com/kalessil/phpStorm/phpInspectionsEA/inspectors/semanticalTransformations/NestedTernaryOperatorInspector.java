package com.kalessil.phpStorm.phpInspectionsEA.inspectors.semanticalTransformations;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jetbrains.php.lang.psi.elements.TernaryExpression;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.GenericPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import org.jetbrains.annotations.NotNull;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class NestedTernaryOperatorInspector extends BasePhpInspection {
    private static final String messageNested = "Nested ternary operator should not be used (maintainability issues).";

    @NotNull
    public String getShortName() {
        return "NestedTernaryOperatorInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new GenericPhpElementVisitor() {
            @Override
            public void visitPhpTernaryExpression(@NotNull TernaryExpression expression) {
                if (this.shouldSkipAnalysis(expression, StrictnessCategory.STRICTNESS_CATEGORY_CODE_STYLE)) { return; }

                final PsiElement condition = ExpressionSemanticUtil.getExpressionTroughParenthesis(expression.getCondition());
                if (condition instanceof TernaryExpression) {
                    holder.registerProblem(condition, messageNested);
                }
                final PsiElement trueVariant = ExpressionSemanticUtil.getExpressionTroughParenthesis(expression.getTrueVariant());
                if (trueVariant instanceof TernaryExpression) {
                    holder.registerProblem(trueVariant, messageNested);
                }
                final PsiElement falseVariant        = expression.getFalseVariant();
                final PsiElement unboxedFalseVariant = ExpressionSemanticUtil.getExpressionTroughParenthesis(falseVariant);
                if (unboxedFalseVariant instanceof TernaryExpression) {
                    final boolean allow = falseVariant instanceof TernaryExpression &&
                                          expression.isShort() &&
                                          ((TernaryExpression) falseVariant).isShort();
                    if (!allow) {
                        holder.registerProblem(unboxedFalseVariant, messageNested);
                    }
                }
            }
        };
    }
}