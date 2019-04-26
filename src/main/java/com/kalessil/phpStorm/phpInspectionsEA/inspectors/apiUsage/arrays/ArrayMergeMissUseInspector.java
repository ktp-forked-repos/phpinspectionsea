package com.kalessil.phpStorm.phpInspectionsEA.inspectors.apiUsage.arrays;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.fixers.UseSuggestedReplacementFixer;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.FeaturedPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiEquivalenceUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class ArrayMergeMissUseInspector extends BasePhpInspection {
    private static final String messageUseArray     = "'[...]' would fit more here (it also much faster).";
    private static final String messageArrayUnshift = "'array_unshift(...)' would fit more here (it also faster).";
    private static final String messageArrayPush    = "'array_push(...)' would fit more here (it also faster).";
    private static final String messageNestedMerge  = "Inlining nested 'array_merge(...)' in arguments is possible here (it also faster).";

    @NotNull
    public String getShortName() {
        return "ArrayMergeMissUseInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new FeaturedPhpElementVisitor() {
            @Override
            public void visitPhpFunctionCall(@NotNull FunctionReference reference) {
                if (this.shouldSkipAnalysis(reference, StrictnessCategory.STRICTNESS_CATEGORY_PERFORMANCE)) { return; }

                final String functionName = reference.getName();
                if (functionName != null && functionName.equals("array_merge")) {
                    final PsiElement[] arguments = reference.getParameters();
                    if (arguments.length >= 2) {
                        if (arguments.length == 2) {
                            if (arguments[0] instanceof ArrayCreationExpression && arguments[1] instanceof ArrayCreationExpression) {
                                /* case 1: `array_merge([], [])` */
                                final List<String> fragments = new ArrayList<>();
                                Stream.of(arguments[0].getChildren()).forEach(c -> fragments.add(c.getText()));
                                Stream.of(arguments[1].getChildren()).forEach(c -> fragments.add(c.getText()));

                                final String replacement = String.format("[%s]", String.join(", ", fragments));
                                holder.registerProblem(reference, messageUseArray, new UseArrayFixer(replacement));

                                fragments.clear();
                            } else if (arguments[0] instanceof ArrayCreationExpression || arguments[1] instanceof ArrayCreationExpression) {
                                /* case 2: `... = array_merge(..., [])`, `... = array_merge([], ...)` */
                                final PsiElement array       = arguments[0] instanceof ArrayCreationExpression ? arguments[0] : arguments[1];
                                final PsiElement destination = arguments[0] instanceof ArrayCreationExpression ? arguments[1] : arguments[0];
                                final PsiElement[] elements  = array.getChildren();
                                if (elements.length > 0 && Arrays.stream(elements).anyMatch(e -> !(e instanceof ArrayHashElement))) {
                                    final PsiElement parent = reference.getParent();
                                    if (OpenapiTypesUtil.isAssignment(parent)) {
                                        final PsiElement container = ((AssignmentExpression) parent).getVariable();
                                        if (container != null && OpenapiEquivalenceUtil.areEqual(container, destination)) {
                                            final List<String> fragments = new ArrayList<>();
                                            if (arguments[0] instanceof ArrayCreationExpression) {
                                                if (destination instanceof Variable) {
                                                    fragments.add(destination.getText());
                                                    Arrays.stream(elements).forEach(e -> fragments.add(e.getText()));
                                                    final String replacement = String.format("array_unshift(%s)", String.join(", ", fragments));
                                                    holder.registerProblem(parent, messageArrayUnshift, new UseArrayUnshiftFixer(replacement));
                                                }
                                            } else {
                                                fragments.add(destination.getText());
                                                Arrays.stream(elements).forEach(e -> fragments.add(e.getText()));
                                                final String replacement = String.format("array_push(%s)", String.join(", ", fragments));
                                                holder.registerProblem(parent, messageArrayPush, new UseArrayPushFixer(replacement));
                                            }
                                            fragments.clear();
                                        }
                                    }
                                }
                            }
                        }

                        /* case 3: `array_merge(..., array_merge(), ...)` */
                        for (final PsiElement argument : arguments) {
                            if (OpenapiTypesUtil.isFunctionReference(argument)) {
                                final String innerFunctionName = ((FunctionReference) argument).getName();
                                if (innerFunctionName != null && innerFunctionName.equals("array_merge")) {
                                    final List<String> fragments = new ArrayList<>();
                                    for (final PsiElement fragment : arguments) {
                                        if (OpenapiTypesUtil.isFunctionReference(fragment)) {
                                            final FunctionReference innerCall = (FunctionReference) fragment;
                                            if (innerFunctionName.equals(innerCall.getName())) {
                                                Arrays.stream(innerCall.getParameters()).forEach(p -> fragments.add(p.getText()));
                                                continue;
                                            }
                                        }
                                        fragments.add(fragment.getText());
                                    }

                                    final String replacement = String.format("array_merge(%s)", String.join(", ", fragments));
                                    holder.registerProblem(reference, messageNestedMerge, new InlineNestedCallsFixer(replacement));

                                    fragments.clear();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        };
    }

    private static final class UseArrayPushFixer extends UseSuggestedReplacementFixer {
        private static final String title = "Use array_push(...) instead";

        @NotNull
        @Override
        public String getName() {
            return title;
        }

        UseArrayPushFixer(@NotNull String expression) {
            super(expression);
        }
    }

    private static final class UseArrayUnshiftFixer extends UseSuggestedReplacementFixer {
        private static final String title = "Use array_unshift(...) instead";

        @NotNull
        @Override
        public String getName() {
            return title;
        }

        UseArrayUnshiftFixer(@NotNull String expression) {
            super(expression);
        }
    }

    private static final class InlineNestedCallsFixer extends UseSuggestedReplacementFixer {
        private static final String title = "Inline nested array_merge(...) calls";

        @NotNull
        @Override
        public String getName() {
            return title;
        }

        InlineNestedCallsFixer(@NotNull String expression) {
            super(expression);
        }
    }

    private static final class UseArrayFixer extends UseSuggestedReplacementFixer {
        private static final String title = "Replace with array declaration";

        @NotNull
        @Override
        public String getName() {
            return title;
        }

        UseArrayFixer(@NotNull String expression) {
            super(expression);
        }
    }
}
