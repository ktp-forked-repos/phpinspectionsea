package com.kalessil.phpStorm.phpInspectionsEA.inspectors.codeStyle;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.tree.IElementType;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.fixers.UseSuggestedReplacementFixer;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.FeaturedPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiEquivalenceUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class UnnecessaryClosureInspector extends BasePhpInspection {
    private static final String messagePattern = "The closure can be replaced with %s (reduces cognitive load).";

    final private static Map<String, Integer> closurePositions     = new HashMap<>();
    final private static Map<IElementType, String> castingsMapping = new HashMap<>();
    static {
        closurePositions.put("array_filter", 1);
        closurePositions.put("array_map", 0);
        closurePositions.put("array_walk", 1);
        closurePositions.put("array_walk_recursive", 1);

        castingsMapping.put(PhpTokenTypes.opINTEGER_CAST, "intval");
        castingsMapping.put(PhpTokenTypes.opFLOAT_CAST, "floatval");
        castingsMapping.put(PhpTokenTypes.opSTRING_CAST, "strval");
        castingsMapping.put(PhpTokenTypes.opBOOLEAN_CAST, "boolval");
    }

    @NotNull
    public String getShortName() {
        return "UnnecessaryClosureInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new FeaturedPhpElementVisitor() {
            @Override
            public void visitPhpFunctionCall(@NotNull FunctionReference reference) {
                if (this.shouldSkipAnalysis(reference, StrictnessCategory.STRICTNESS_CATEGORY_CODE_STYLE)) { return; }

                final String functionName = reference.getName();
                if (functionName != null && closurePositions.containsKey(functionName)) {
                    final int targetPosition     = closurePositions.get(functionName);
                    final PsiElement[] arguments = reference.getParameters();
                    if (arguments.length > targetPosition && OpenapiTypesUtil.isLambda(arguments[targetPosition])) {
                        final PsiElement expression = arguments[targetPosition];
                        final Function closure      = (Function) (expression instanceof Function ? expression : expression.getFirstChild());
                        if (closure.getParameters().length > 0) {
                            final GroupStatement body = ExpressionSemanticUtil.getGroupStatement(closure);
                            if (body != null && ExpressionSemanticUtil.countExpressionsInGroup(body) == 1) {
                                final PsiElement last = ExpressionSemanticUtil.getLastStatement(body);
                                if (last != null) {
                                    final PsiElement candidate = this.getCandidate(last);
                                    if (candidate instanceof FunctionReference) {
                                        final FunctionReference callback = (FunctionReference) candidate;
                                        if (this.canInline(callback, closure)) {
                                            final String replacement = String.format(
                                                    "'%s%s'",
                                                    callback.getImmediateNamespaceName(),
                                                    callback.getName()
                                            );
                                            holder.registerProblem(
                                                    expression,
                                                    String.format(messagePattern, replacement),
                                                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                                                    new UseCallbackFix(replacement)
                                            );
                                        }
                                    } else if (candidate instanceof UnaryExpression) {
                                        final PsiElement operation = ((UnaryExpression) candidate).getOperation();
                                        if (operation != null) {
                                            final IElementType operator = operation.getNode().getElementType();
                                            if (castingsMapping.containsKey(operator)) {
                                                final String replacement = String.format("'%s'", castingsMapping.get(operator));
                                                holder.registerProblem(
                                                        expression,
                                                        String.format(messagePattern, replacement),
                                                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                                                        new UseCallbackFix(replacement)
                                                );
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            @Nullable
            private PsiElement getCandidate(@NotNull PsiElement last) {
                if (last instanceof PhpReturn) {
                    final PsiElement candidate = ExpressionSemanticUtil.getReturnValue((PhpReturn) last);
                    if (candidate instanceof UnaryExpression || OpenapiTypesUtil.isFunctionReference(candidate)) {
                        return candidate;
                    }
                } else if (OpenapiTypesUtil.isStatementImpl(last) && OpenapiTypesUtil.isAssignment(last.getFirstChild())) {
                    final AssignmentExpression assignment = (AssignmentExpression) last.getFirstChild();
                    final PsiElement container            = assignment.getVariable();
                    if (container instanceof Variable) {
                        final PsiElement value = assignment.getValue();
                        if (OpenapiTypesUtil.isFunctionReference(value)) {
                            final FunctionReference candidate = (FunctionReference) value;
                            final PsiElement[] arguments      = candidate.getParameters();
                            if (arguments.length == 1 && OpenapiEquivalenceUtil.areEqual(container, arguments[0])) {
                                return candidate;
                            }
                        }
                    }
                }
                return null;
            }

            private boolean canInline(@NotNull FunctionReference callback, @NotNull Function closure) {
                boolean result               = false;
                final PsiElement[] arguments = callback.getParameters();
                if (arguments.length == 1) {
                    final Parameter[] input = closure.getParameters();
                    if (input.length >= arguments.length && Arrays.stream(arguments).allMatch(a -> a instanceof Variable)) {
                        result = true;
                        for (int index = 0; index < arguments.length; ++index) {
                            final Variable argument       = (Variable) arguments[index];
                            final boolean isEnforcingType = !input[index].getDeclaredType().isEmpty();
                            if (isEnforcingType || !argument.getName().equals(input[index].getName())) {
                                result = false;
                                break;
                            }
                        }
                    }
                }
                return result;
            }
        };
    }

    private static final class UseCallbackFix extends UseSuggestedReplacementFixer {
        private static final String title = "Inline the closure";

        @NotNull
        @Override
        public String getName() {
            return title;
        }

        UseCallbackFix(@NotNull String expression) {
            super(expression);
        }
    }
}
