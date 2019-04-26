package com.kalessil.phpStorm.phpInspectionsEA.inspectors.languageConstructions.nullCoalescing;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Couple;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.tree.IElementType;
import com.jetbrains.php.config.PhpLanguageFeature;
import com.jetbrains.php.config.PhpLanguageLevel;
import com.jetbrains.php.config.PhpProjectConfigurationFacade;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.PhpPsiElementFactory;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.fixers.UseSuggestedReplacementFixer;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.FeaturedPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.OptionsComponent;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiEquivalenceUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.PhpLanguageUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class NullCoalescingOperatorCanBeUsedInspector extends BasePhpInspection {
    // Inspection options.
    public boolean SUGGEST_SIMPLIFYING_TERNARIES = true;
    public boolean SUGGEST_SIMPLIFYING_IFS       = true;

    private static final String messagePattern = "'%s' can be used instead (reduces cognitive load).";

    @NotNull
    public String getShortName() {
        return "NullCoalescingOperatorCanBeUsedInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new FeaturedPhpElementVisitor() {
            @Override
            public void visitPhpTernaryExpression(@NotNull TernaryExpression expression) {
                if (this.shouldSkipAnalysis(expression, StrictnessCategory.STRICTNESS_CATEGORY_LANGUAGE_LEVEL_MIGRATION)) { return; }

                final PhpLanguageLevel php = PhpProjectConfigurationFacade.getInstance(holder.getProject()).getLanguageLevel();
                if (SUGGEST_SIMPLIFYING_TERNARIES && php.hasFeature(PhpLanguageFeature.COALESCE_OPERATOR) && !expression.isShort()) {
                    final PsiElement condition = ExpressionSemanticUtil.getExpressionTroughParenthesis(expression.getCondition());
                    if (condition != null) {
                        final PsiElement extracted = this.getTargetCondition(condition);
                        if (extracted != null) {
                            final PsiElement firstValue  = expression.getTrueVariant();
                            final PsiElement secondValue = expression.getFalseVariant();
                            if (firstValue != null && secondValue != null) {
                                final String replacement = this.generateReplacement(condition, extracted, firstValue, secondValue);
                                if (replacement != null) {
                                    holder.registerProblem(
                                            expression,
                                            String.format(messagePattern, replacement),
                                            new ReplaceSingleConstructFix(replacement)
                                    );
                                }
                            }
                        }
                    }
                }
            }

            @Override
            public void visitPhpIf(@NotNull If statement) {
                if (this.shouldSkipAnalysis(statement, StrictnessCategory.STRICTNESS_CATEGORY_LANGUAGE_LEVEL_MIGRATION)) { return; }

                final PhpLanguageLevel php = PhpProjectConfigurationFacade.getInstance(holder.getProject()).getLanguageLevel();
                if (SUGGEST_SIMPLIFYING_IFS && php.hasFeature(PhpLanguageFeature.COALESCE_OPERATOR)) {
                    final PsiElement condition = ExpressionSemanticUtil.getExpressionTroughParenthesis(statement.getCondition());
                    if (condition != null && statement.getElseIfBranches().length == 0) {
                        final PsiElement extracted = this.getTargetCondition(condition);
                        if (extracted != null) {
                            final Couple<Couple<PsiElement>> fragments = this.extract(statement);
                            final PsiElement firstValue                = fragments.second.first;
                            final PsiElement secondValue               = fragments.second.second;
                            if (firstValue != null && secondValue != null) {
                                final String coalescing = this.generateReplacement(condition, extracted, firstValue, secondValue);
                                if (coalescing != null) {
                                    final PsiElement context = firstValue.getParent();
                                    if (context instanceof PhpReturn) {
                                        final String replacement = String.format("return %s", coalescing);
                                        holder.registerProblem(
                                                statement.getFirstChild(),
                                                String.format(messagePattern, replacement),
                                                new ReplaceMultipleConstructFix(fragments.first.first, fragments.first.second, replacement)
                                        );
                                    } else if (context instanceof AssignmentExpression) {
                                        final PsiElement container = ((AssignmentExpression) context).getVariable();
                                        final String replacement   = String.format("%s = %s", container.getText(), coalescing);
                                        holder.registerProblem(
                                                statement.getFirstChild(),
                                                String.format(messagePattern, replacement),
                                                new ReplaceMultipleConstructFix(fragments.first.first, fragments.first.second, replacement)
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
            }

            @Nullable
            private String generateReplacement(
                    @NotNull PsiElement condition,
                    @NotNull PsiElement extracted,
                    @NotNull PsiElement first,
                    @NotNull PsiElement second
            ) {
                String coalescing = null;
                if (extracted instanceof PhpIsset) {
                    coalescing = this.generateReplacementForIsset(condition, (PhpIsset) extracted, first, second);
                } else if (extracted instanceof FunctionReference) {
                    coalescing = this.generateReplacementForExists(condition, (FunctionReference) extracted, first, second);
                } else if (extracted instanceof BinaryExpression) {
                    coalescing = this.generateReplacementForIdentity(condition, (BinaryExpression) extracted, first, second);
                }
                return coalescing;
            }

            @Nullable
            private String generateReplacementForExists(
                    @NotNull PsiElement condition,
                    @NotNull FunctionReference extracted,
                    @NotNull PsiElement first,
                    @NotNull PsiElement second
            ) {
                final PsiElement[] arguments = extracted.getParameters();
                if (arguments.length == 2) {
                    final boolean expectsToBeSet = condition == extracted;
                    final PsiElement candidate   = expectsToBeSet ? first : second;
                    final PsiElement alternative = expectsToBeSet ? second : first;
                    if (candidate instanceof ArrayAccessExpression && PhpLanguageUtil.isNull(alternative)) {
                        final ArrayAccessExpression access = (ArrayAccessExpression) candidate;
                        final PsiElement container         = access.getValue();
                        if (container != null && OpenapiEquivalenceUtil.areEqual(container, arguments[1])) {
                            final ArrayIndex index = access.getIndex();
                            if (index != null) {
                                final PsiElement key = index.getValue();
                                if (key != null && OpenapiEquivalenceUtil.areEqual(key, arguments[0])) {
                                    return String.format("%s ?? %s", candidate.getText(), alternative.getText());
                                }
                            }
                        }
                    }
                }
                return null;
            }

            @Nullable
            private String generateReplacementForIdentity(
                    @NotNull PsiElement condition,
                    @NotNull BinaryExpression extracted,
                    @NotNull PsiElement first,
                    @NotNull PsiElement second
            ) {
                PsiElement subject = extracted.getLeftOperand();
                if (PhpLanguageUtil.isNull(subject)) {
                    subject = extracted.getRightOperand();
                }
                if (subject != null) {
                    final IElementType operator  = extracted.getOperationType();
                    final boolean expectsToBeSet = (operator == PhpTokenTypes.opNOT_IDENTICAL && condition == extracted) ||
                                                   (operator == PhpTokenTypes.opIDENTICAL && condition != extracted);
                    final PsiElement candidate   = expectsToBeSet ? first : second;
                    if (OpenapiEquivalenceUtil.areEqual(candidate, subject)) {
                        final PsiElement alternative = expectsToBeSet ? second : first;
                        return String.format("%s ?? %s", candidate.getText(), alternative.getText());
                    }
                }
                return null;
            }

            @Nullable
            private String generateReplacementForIsset(
                    @NotNull PsiElement condition,
                    @NotNull PhpIsset extracted,
                    @NotNull PsiElement first,
                    @NotNull PsiElement second
            ) {
                final PsiElement subject = extracted.getVariables()[0];
                if (subject != null) {
                    final boolean expectsToBeSet = condition == extracted;
                    final PsiElement candidate   = expectsToBeSet ? first : second;
                    if (OpenapiEquivalenceUtil.areEqual(candidate, subject)) {
                        final PsiElement alternative = expectsToBeSet ? second : first;
                        return String.format("%s ?? %s", candidate.getText(), alternative.getText());
                    }
                }
                return null;
            }

            @Nullable
            private PsiElement getTargetCondition(@NotNull PsiElement condition) {
                /* un-wrap inverted conditions */
                if (condition instanceof UnaryExpression) {
                    final UnaryExpression unary = (UnaryExpression) condition;
                    if (OpenapiTypesUtil.is(unary.getOperation(), PhpTokenTypes.opNOT)) {
                        condition = ExpressionSemanticUtil.getExpressionTroughParenthesis(unary.getValue());
                    }
                }
                /* do check */
                if (condition instanceof PhpIsset) {
                    final PhpIsset isset = (PhpIsset) condition;
                    if (isset.getVariables().length == 1) {
                        return condition;
                    }
                } else if (condition instanceof BinaryExpression) {
                    final BinaryExpression binary = (BinaryExpression) condition;
                    final IElementType operator   = binary.getOperationType();
                    if (operator == PhpTokenTypes.opIDENTICAL || operator == PhpTokenTypes.opNOT_IDENTICAL) {
                        if (PhpLanguageUtil.isNull(binary.getRightOperand())) {
                            return condition;
                        } else if (PhpLanguageUtil.isNull(binary.getLeftOperand())) {
                            return condition;
                        }
                    }
                } else if (OpenapiTypesUtil.isFunctionReference(condition)) {
                    final String functionName = ((FunctionReference) condition).getName();
                    if (functionName != null && functionName.equals("array_key_exists")) {
                        return condition;
                    }
                }
                return null;
            }

            /* first pair: what to drop, second positive and negative branching values */
            private Couple<Couple<PsiElement>> extract(@NotNull If statement) {
                Couple<Couple<PsiElement>> result = new Couple<>(new Couple<>(null, null), new Couple<>(null, null));

                final GroupStatement ifBody = ExpressionSemanticUtil.getGroupStatement(statement);
                if (ifBody != null && ExpressionSemanticUtil.countExpressionsInGroup(ifBody) == 1) {
                    final PsiElement ifLast = this.extractCandidate(ExpressionSemanticUtil.getLastStatement(ifBody));
                    if (ifLast != null) {
                        /* extract all related constructs */
                        final PsiElement ifNext     = this.extractCandidate(statement.getNextPsiSibling());
                        final PsiElement ifPrevious = this.extractCandidate(statement.getPrevPsiSibling());

                        if (statement.getElseBranch() != null) {
                            PsiElement elseLast           = null;
                            final GroupStatement elseBody = ExpressionSemanticUtil.getGroupStatement(statement.getElseBranch());
                            if (elseBody != null && ExpressionSemanticUtil.countExpressionsInGroup(elseBody) == 1) {
                                elseLast = this.extractCandidate(ExpressionSemanticUtil.getLastStatement(elseBody));
                            }

                            /* if - return - else - return */
                            if (ifLast instanceof PhpReturn && elseLast instanceof PhpReturn) {
                                result = new Couple<>(
                                        new Couple<>(statement, statement),
                                        new Couple<>(((PhpReturn) ifLast).getArgument(), ((PhpReturn) elseLast).getArgument())
                                );
                            }
                            /* if - assign - else - assign */
                            else if (ifLast instanceof AssignmentExpression && elseLast instanceof AssignmentExpression) {
                                final AssignmentExpression ifAssignment   = (AssignmentExpression) ifLast;
                                final AssignmentExpression elseAssignment = (AssignmentExpression) elseLast;
                                final PsiElement ifContainer              = ifAssignment.getVariable();
                                final PsiElement elseContainer            = elseAssignment.getVariable();
                                if (ifContainer instanceof Variable && elseContainer instanceof Variable) {
                                    final boolean isTarget = OpenapiEquivalenceUtil.areEqual(ifContainer, elseContainer);
                                    if (isTarget) {
                                        result = new Couple<>(
                                                new Couple<>(statement, statement),
                                                new Couple<>(ifAssignment.getValue(), elseAssignment.getValue())
                                        );
                                    }
                                }
                            }
                        } else {
                            /* assign - if - assign */
                            if (ifPrevious instanceof AssignmentExpression && ifLast instanceof AssignmentExpression) {
                                final AssignmentExpression previousAssignment = (AssignmentExpression) ifPrevious;
                                final AssignmentExpression ifAssignment       = (AssignmentExpression) ifLast;
                                final PsiElement previousContainer            = previousAssignment.getVariable();
                                final PsiElement ifContainer                  = ifAssignment.getVariable();
                                if (previousContainer instanceof Variable && ifContainer instanceof Variable) {
                                    final boolean isTarget = OpenapiEquivalenceUtil.areEqual(previousContainer, ifContainer);
                                    if (isTarget && !OpenapiTypesUtil.isAssignmentByReference(previousAssignment)) {
                                        final PsiElement previousValue = previousAssignment.getValue();
                                        if (!(previousValue instanceof AssignmentExpression)) {
                                            result = new Couple<>(
                                                    new Couple<>(ifPrevious.getParent(), statement),
                                                    new Couple<>(ifAssignment.getValue(), previousValue)
                                            );
                                        }
                                    }
                                }
                            }
                            /* if - return - return */
                            else if (ifLast instanceof PhpReturn && ifNext instanceof PhpReturn) {
                                result = new Couple<>(
                                        new Couple<>(statement, ifNext),
                                        new Couple<>(((PhpReturn) ifLast).getArgument(), ((PhpReturn) ifNext).getArgument())
                                );
                            }
                        }
                    }
                }
                return result;
            }

            @Nullable
            private PsiElement extractCandidate(@Nullable PsiElement statement) {
                if (statement instanceof PhpReturn) {
                    return statement;
                } else if (OpenapiTypesUtil.isStatementImpl(statement)) {
                    final PsiElement possiblyAssignment = statement.getFirstChild();
                    if (OpenapiTypesUtil.isAssignment(possiblyAssignment)) {
                        final AssignmentExpression assignment = (AssignmentExpression) possiblyAssignment;
                        final PsiElement container             = assignment.getVariable();
                        if (container instanceof Variable) {
                            return assignment;
                        }
                    }
                }
                return null;
            }
        };
    }

    public JComponent createOptionsPanel() {
        return OptionsComponent.create((component) -> {
            component.addCheckbox("Simplify ternary expressions", SUGGEST_SIMPLIFYING_TERNARIES, (isSelected) -> SUGGEST_SIMPLIFYING_TERNARIES = isSelected);
            component.addCheckbox("Simplify if-statements", SUGGEST_SIMPLIFYING_IFS, (isSelected) -> SUGGEST_SIMPLIFYING_IFS = isSelected);
        });
    }

    private static final class ReplaceSingleConstructFix extends UseSuggestedReplacementFixer {
        private static final String title = "Use null coalescing operator instead";

        @NotNull
        @Override
        public String getName() {
            return title;
        }

        ReplaceSingleConstructFix(@NotNull String expression) {
            super(expression);
        }
    }

    private static final class ReplaceMultipleConstructFix implements LocalQuickFix {
        private static final String title = "Replace with null coalescing operator";

        final private SmartPsiElementPointer<PsiElement> from;
        final private SmartPsiElementPointer<PsiElement> to;
        final String replacement;

        ReplaceMultipleConstructFix(@NotNull PsiElement from, @NotNull PsiElement to, @NotNull String replacement) {
            super();
            final SmartPointerManager factory = SmartPointerManager.getInstance(from.getProject());

            this.from        = factory.createSmartPsiElementPointer(from);
            this.to          = factory.createSmartPsiElementPointer(to);
            this.replacement = replacement;
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
            final PsiElement from = this.from.getElement();
            final PsiElement to   = this.to.getElement();
            if (from != null && to != null && !project.isDisposed()) {
                final String code = this.replacement + ';';
                if (from == to) {
                    final boolean wrap       = from instanceof If && from.getParent() instanceof Else;
                    final String replacement = wrap ? "{ " + this.replacement + "; }" : this.replacement + ";";
                    from.replace(PhpPsiElementFactory.createStatement(project, replacement));
                } else {
                    final PsiElement parent = from.getParent();
                    parent.addBefore(PhpPsiElementFactory.createStatement(project, code), from);
                    parent.deleteChildRange(from, to);
                }
            }
        }
    }
}