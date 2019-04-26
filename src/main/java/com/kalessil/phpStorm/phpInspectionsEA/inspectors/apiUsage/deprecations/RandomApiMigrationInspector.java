package com.kalessil.phpStorm.phpInspectionsEA.inspectors.apiUsage.deprecations;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jetbrains.php.config.PhpLanguageFeature;
import com.jetbrains.php.config.PhpLanguageLevel;
import com.jetbrains.php.config.PhpProjectConfigurationFacade;
import com.jetbrains.php.lang.psi.elements.FunctionReference;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.GenericPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.OptionsComponent;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
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

public class RandomApiMigrationInspector extends BasePhpInspection {
    // Inspection options.
    public boolean SUGGEST_USING_RANDOM_INT = true;

    private static final String messagePattern = "'%s(...)' has recommended replacement '%s(...)', consider migrating.";

    @NotNull
    public String getShortName() {
        return "RandomApiMigrationInspection";
    }

    private static final Map<String, String> mappingMt   = new HashMap<>();
    private static final Map<String, String> mappingEdge = new HashMap<>();
    static {
        mappingMt.put("srand",        "mt_srand");
        mappingMt.put("getrandmax",   "mt_getrandmax");
        mappingMt.put("rand",         "mt_rand");

        mappingEdge.put("srand",      "mt_srand");
        mappingEdge.put("getrandmax", "mt_getrandmax");
        mappingEdge.put("rand",       "random_int");
        mappingEdge.put("mt_rand",    "random_int");
    }

    @NotNull
    private Map<String, String> getMapping(@NotNull PhpLanguageLevel php) {
        if (SUGGEST_USING_RANDOM_INT && php.hasFeature(PhpLanguageFeature.SCALAR_TYPE_HINTS)) {
            return mappingEdge;
        }
        return mappingMt;
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new GenericPhpElementVisitor() {
            @Override
            public void visitPhpFunctionCall(@NotNull FunctionReference reference) {
                if (this.shouldSkipAnalysis(reference, StrictnessCategory.STRICTNESS_CATEGORY_LANGUAGE_LEVEL_MIGRATION)) { return; }

                final String functionName = reference.getName();
                if (functionName != null) {
                    final PhpLanguageLevel php = PhpProjectConfigurationFacade.getInstance(holder.getProject()).getLanguageLevel();
                    String suggestion          = getMapping(php).get(functionName);
                    if (suggestion != null && this.isFromRootNamespace(reference)) {
                        /* random_int needs 2 parameters always, so check if mt_rand can be suggested */
                        if (reference.getParameters().length != 2 && suggestion.equals("random_int")) {
                            if (functionName.equals("rand")) {
                                suggestion = "mt_rand";
                            } else {
                                return;
                            }
                        }

                        holder.registerProblem(
                                reference,
                                String.format(messagePattern, functionName, suggestion),
                                new ModernizeCallFixer(suggestion)
                        );
                    }
                }
            }
        };
    }

    public JComponent createOptionsPanel() {
        return OptionsComponent.create(
            (component) -> component.addCheckbox("Suggest using random_int", SUGGEST_USING_RANDOM_INT, (isSelected) -> SUGGEST_USING_RANDOM_INT = isSelected)
        );
    }

    private static final class ModernizeCallFixer implements LocalQuickFix {
        private static final String title = "Use the recommended function";

        final private String suggestedName;

        ModernizeCallFixer(@NotNull String suggestedName) {
            super();
            this.suggestedName = suggestedName;
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
            final PsiElement expression = descriptor.getPsiElement();
            if (expression instanceof FunctionReference && !project.isDisposed()) {
                ((FunctionReference) expression).handleElementRename(this.suggestedName);
            }
        }
    }
}
